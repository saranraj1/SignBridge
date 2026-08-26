package com.signbridge.app.preprocessing

import com.signbridge.app.vision.HandLandmarkData
import com.signbridge.app.vision.LandmarkPoint
import com.signbridge.app.vision.VisionFrameResult
import kotlin.math.sqrt

/**
 * Normalizes raw hand landmark coordinates to make gesture representations
 * invariant to translation (hand position in frame) and scale (distance from camera).
 */
class LandmarkNormalizer(
    private val minValidScale: Float = MIN_SCALE_THRESHOLD
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
        // Why index 9? The palm bone structure from wrist (0) to middle MCP (9) is anatomically
        // rigid and invariant to finger flexion, curling, or articulation.
        val middleMcp = rawLandmarks[MIDDLE_MCP_INDEX]

        val dxScale = middleMcp.x - wrist.x
        val dyScale = middleMcp.y - wrist.y
        val dzScale = middleMcp.z - wrist.z
        val handScale = sqrt(dxScale * dxScale + dyScale * dyScale + dzScale * dzScale)

        // Safety check: protect against division by zero or degenerate near-zero hands
        if (handScale < minValidScale || !handScale.isFinite()) {
            return null
        }

        // 3. Perform translation and scale normalization for all 21 landmarks
        val normalizedPoints = ArrayList<NormalizedLandmarkPoint>(21)
        for (landmark in rawLandmarks) {
            val transX = landmark.x - wrist.x
            val transY = landmark.y - wrist.y
            val transZ = landmark.z - wrist.z

            val normX = transX / handScale
            val normY = transY / handScale
            val normZ = transZ / handScale

            normalizedPoints.add(NormalizedLandmarkPoint(normX, normY, normZ))
        }

        return NormalizedLandmarkFrame(
            timestampMs = timestampMs,
            handedness = hand.handedness,
            landmarks = normalizedPoints,
            handScale = handScale,
            rawWristPosition = wrist
        )
    }

    companion object {
        const val WRIST_INDEX = 0
        const val MIDDLE_MCP_INDEX = 9
        const val MIN_SCALE_THRESHOLD = 1e-5f
    }
}
