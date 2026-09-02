package com.signbridge.app.preprocessing

import android.util.Log
import com.signbridge.app.vision.HandLandmarkData
import com.signbridge.app.vision.LandmarkPoint
import com.signbridge.app.vision.VisionFrameResult
import kotlin.math.sqrt

/**
 * Normalizes raw hand landmark coordinates to make gesture representations
 * invariant to translation (hand position in frame), scale (distance from camera),
 * and — optionally — handedness (left vs right hand).
 *
 * FIX #2: Added handedness normalization.
 * Without it, teaching with a Right hand and performing with a Left hand (or vice versa)
 * produces feature vectors that are mirror images of each other. Even with perfect
 * in-plane rotation alignment the 63-D shape vectors differ significantly across hands.
 * When mirrorToRightHand=true, Left-hand landmarks are reflected about the X axis so
 * both hands produce structurally identical feature vectors.
 *
 * Toggle mirrorToRightHand=false to restore the original behaviour (e.g. if your
 * use-case always uses the same hand and you want to distinguish left/right signs).
 */
class LandmarkNormalizer(
    private val minValidScale: Float = MIN_SCALE_THRESHOLD,
    /** When true, Left-hand frames are reflected to match Right-hand geometry. */
    private val mirrorToRightHand: Boolean = true
) {

    /**
     * Normalizes the primary detected hand from a [VisionFrameResult].
     *
     * @param visionResult Raw vision output from camera inference
     * @return [NormalizedLandmarkFrame] if a valid primary hand exists, or null if no hand or invalid
     */
    fun normalize(visionResult: VisionFrameResult): NormalizedLandmarkFrame? {
        if (!visionResult.hasHands) return null

        // Primary hand policy: select hand with highest confidence score (or first hand)
        val primaryHand = visionResult.hands.maxByOrNull { it.score } ?: visionResult.hands[0]
        return normalizeHand(primaryHand, visionResult.timestampMs)
    }

    /**
     * Normalizes a single [HandLandmarkData] instance into a [NormalizedLandmarkFrame].
     *
     * @param hand Raw hand landmark data containing 21 points
     * @param timestampMs Frame timestamp in milliseconds
     * @return [NormalizedLandmarkFrame] if valid, or null if landmarks are invalid/degenerate
     */
    fun normalizeHand(hand: HandLandmarkData, timestampMs: Long): NormalizedLandmarkFrame? {
        val rawLandmarks = hand.landmarks
        if (rawLandmarks.size != 21) return null

        // Validate that no coordinates contain NaN or Infinite values
        for (point in rawLandmarks) {
            if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) {
                return null
            }
        }

        // 1. Reference landmark for translation: Wrist (index 0)
        val wrist = rawLandmarks[WRIST_INDEX]

        // 2. Reference landmark for scale: Middle-finger MCP (index 9)
        val middleMcp = rawLandmarks[MIDDLE_MCP_INDEX]

        val dxScale = middleMcp.x - wrist.x
        val dyScale = middleMcp.y - wrist.y
        val dzScale = middleMcp.z - wrist.z
        val handScale = sqrt(dxScale * dxScale + dyScale * dyScale + dzScale * dzScale)

        // Safety check: protect against division by zero or degenerate near-zero hands
        if (handScale < minValidScale || !handScale.isFinite()) {
            return null
        }

        // 3. Compute in-plane rotation alignment
        // Aligns the vector from Wrist (0) to Middle MCP (9) with the vertical axis (0, -1, 0)
        val len2D = sqrt(dxScale * dxScale + dyScale * dyScale)
        val cosPhi: Float
        val sinPhi: Float
        if (len2D > 1e-5f) {
            cosPhi = -dyScale / len2D
            sinPhi = -dxScale / len2D
        } else {
            cosPhi = 1f
            sinPhi = 0f
        }

        // FIX #2: determine if we need to mirror this hand.
        // MediaPipe reports "Left" for the user's left hand regardless of camera mirroring.
        val isLeftHand = hand.handedness.equals("Left", ignoreCase = true)
        val shouldMirror = mirrorToRightHand && isLeftHand

        // 4. Perform translation, in-plane rotation, and scale normalization for all 21 landmarks
        val normalizedPoints = ArrayList<NormalizedLandmarkPoint>(21)
        for (landmark in rawLandmarks) {
            val transX = landmark.x - wrist.x
            val transY = landmark.y - wrist.y
            val transZ = landmark.z - wrist.z

            // Rotate around wrist in 2D image plane
            val rotX = transX * cosPhi - transY * sinPhi
            val rotY = transX * sinPhi + transY * cosPhi

            var normX = rotX / handScale
            val normY = rotY / handScale
            val normZ = transZ / handScale

            // FIX #2: Mirror X axis so left-hand produces the same shape as right-hand.
            // This reflection is applied AFTER rotation so it doesn't disturb alignment.
            if (shouldMirror) {
                normX = -normX
            }

            normalizedPoints.add(NormalizedLandmarkPoint(normX, normY, normZ))
        }

        // FIX #2: Store the effective handedness so downstream code can log/track it,
        // but override to "Right" when mirroring so the segmenter's handedness
        // consistency check doesn't flag left vs right as a mismatch.
        val effectiveHandedness = if (shouldMirror) "Right" else hand.handedness

        return NormalizedLandmarkFrame(
            timestampMs = timestampMs,
            handedness = effectiveHandedness,
            landmarks = normalizedPoints,
            handScale = handScale,
            rawWristPosition = wrist
        )
    }

    /**
     * Diagnostic logger for verifying normalization properties.
     */
    fun logDiagnosticVerification(rawHand: HandLandmarkData, normFrame: NormalizedLandmarkFrame) {
        val rawWrist = rawHand.landmarks[WRIST_INDEX]
        val rawMiddle = rawHand.landmarks[MIDDLE_MCP_INDEX]
        val normWrist = normFrame.landmarks[WRIST_INDEX]
        val normMiddle = normFrame.landmarks[MIDDLE_MCP_INDEX]

        val normDist = sqrt(
            (normMiddle.x - normWrist.x) * (normMiddle.x - normWrist.x) +
                    (normMiddle.y - normWrist.y) * (normMiddle.y - normWrist.y) +
                    (normMiddle.z - normWrist.z) * (normMiddle.z - normWrist.z)
        )

        Log.d(
            "M4ForensicNorm",
            "NORM VERIFY: scale=${String.format("%.4f", normFrame.handScale)} | " +
                    "rawWrist=(${String.format("%.3f", rawWrist.x)}, ${String.format("%.3f", rawWrist.y)}, ${String.format("%.3f", rawWrist.z)}) | " +
                    "normWrist=(${String.format("%.3f", normWrist.x)}, ${String.format("%.3f", normWrist.y)}, ${String.format("%.3f", normWrist.z)}) | " +
                    "normMiddle=(${String.format("%.3f", normMiddle.x)}, ${String.format("%.3f", normMiddle.y)}, ${String.format("%.3f", normMiddle.z)}) | " +
                    "normMiddleDist=${String.format("%.4f", normDist)} (should be 1.0000) | " +
                    "mirrored=${rawHand.handedness.equals("Left", ignoreCase = true) && mirrorToRightHand}"
        )
    }

    companion object {
        const val WRIST_INDEX = 0
        const val MIDDLE_MCP_INDEX = 9
        const val MIN_SCALE_THRESHOLD = 1e-5f
    }
}
