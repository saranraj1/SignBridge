package com.signbridge.app.preprocessing

import com.signbridge.app.vision.HandLandmarkData
import com.signbridge.app.vision.LandmarkPoint
import com.signbridge.app.vision.VisionFrameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LandmarkNormalizerTest {

    private val normalizer = LandmarkNormalizer()

    private fun createSyntheticHand(
        wristX: Float,
        wristY: Float,
        wristZ: Float,
        scale: Float
    ): HandLandmarkData {
        val landmarks = ArrayList<LandmarkPoint>(21)
        for (i in 0 until 21) {
            landmarks.add(
                LandmarkPoint(
                    x = wristX + (i * 0.02f * scale),
                    y = wristY + (i * 0.03f * scale),
                    z = wristZ + (i * 0.01f * scale)
                )
            )
        }
        return HandLandmarkData(
            handedness = "Right",
            score = 0.95f,
            landmarks = landmarks
        )
    }

    /**
     * TEST 1: Translation normalization.
     * Given known landmarks and wrist position, verify wrist landmark 0 becomes (0, 0, 0).
     */
    @Test
    fun testTranslationNormalization_wristAtOrigin() {
        val rawHand = createSyntheticHand(wristX = 0.65f, wristY = 0.40f, wristZ = -0.15f, scale = 1.0f)
        val normalized = normalizer.normalizeHand(rawHand, timestampMs = 1000L)

        assertNotNull("Normalized frame should not be null", normalized)
        val wristNorm = normalized!!.landmarks[0]

        assertEquals(0.0f, wristNorm.x, 1e-5f)
        assertEquals(0.0f, wristNorm.y, 1e-5f)
        assertEquals(0.0f, wristNorm.z, 1e-5f)
    }

    /**
     * TEST 2: Scale normalization.
     * Given a hand twice as large (or at different camera distances),
     * verify normalized coordinates remain invariant/identical.
     */
    @Test
    fun testScaleNormalization_scaleInvariance() {
        val handScale1 = createSyntheticHand(wristX = 0.5f, wristY = 0.5f, wristZ = 0.0f, scale = 1.0f)
        val handScale2 = createSyntheticHand(wristX = 0.2f, wristY = 0.8f, wristZ = 0.1f, scale = 2.5f)

        val norm1 = normalizer.normalizeHand(handScale1, 1000L)
        val norm2 = normalizer.normalizeHand(handScale2, 2000L)

        assertNotNull(norm1)
        assertNotNull(norm2)

        for (i in 0 until 21) {
            val p1 = norm1!!.landmarks[i]
            val p2 = norm2!!.landmarks[i]

            assertEquals("Landmark $i X mismatch", p1.x, p2.x, 1e-4f)
            assertEquals("Landmark $i Y mismatch", p1.y, p2.y, 1e-4f)
            assertEquals("Landmark $i Z mismatch", p1.z, p2.z, 1e-4f)
        }
    }

    /**
     * TEST 3: Zero/near-zero scale handling.
     * Verify degenerate points where middle MCP equals wrist do not cause division by zero or NaN.
     */
    @Test
    fun testZeroScaleHandling_noNaNOrCrash() {
        // All 21 landmarks at the exact same location (scale = 0)
        val degeneratePoints = (0 until 21).map { LandmarkPoint(0.5f, 0.5f, 0.0f) }
        val degenerateHand = HandLandmarkData(
            handedness = "Right",
            score = 0.9f,
            landmarks = degeneratePoints
        )

        val result = normalizer.normalizeHand(degenerateHand, 1000L)
        // Should safely return null without throwing an exception or producing NaNs
        assertNull("Degenerate zero-scale hand should safely return null", result)
    }

    /**
     * TEST 5: In-plane rotation normalization (Tilt Invariance).
     * Given a hand tilted at 0°, 30°, and -45°, verify that after normalization,
     * the landmarks produce identical coordinates.
     */
    @Test
    fun testRotationNormalization_tiltInvariance() {
        val wristX = 0.5f
        val wristY = 0.6f
        val scale = 0.2f

        // Base untilted hand points
        val basePoints = ArrayList<LandmarkPoint>(21)
        for (i in 0 until 21) {
            val localX = (i % 4 - 1.5f) * 0.03f * scale
            val localY = -(i * 0.04f * scale)
            val localZ = (i * 0.005f * scale)
            basePoints.add(LandmarkPoint(wristX + localX, wristY + localY, localZ))
        }

        // Tilted hand points (rotated by 35 degrees around wrist)
        val angleRad = Math.toRadians(35.0)
        val cosA = kotlin.math.cos(angleRad).toFloat()
        val sinA = kotlin.math.sin(angleRad).toFloat()

        val tiltedPoints = ArrayList<LandmarkPoint>(21)
        for (p in basePoints) {
            val dx = p.x - wristX
            val dy = p.y - wristY
            val rotX = dx * cosA - dy * sinA
            val rotY = dx * sinA + dy * cosA
            tiltedPoints.add(LandmarkPoint(wristX + rotX, wristY + rotY, p.z))
        }

        val handBase = HandLandmarkData("Right", 0.95f, basePoints)
        val handTilted = HandLandmarkData("Right", 0.95f, tiltedPoints)

        val normBase = normalizer.normalizeHand(handBase, 1000L)
        val normTilted = normalizer.normalizeHand(handTilted, 1000L)

        assertNotNull(normBase)
        assertNotNull(normTilted)

        for (i in 0 until 21) {
            val p1 = normBase!!.landmarks[i]
            val p2 = normTilted!!.landmarks[i]

            assertEquals("Tilt invariant X mismatch at index $i", p1.x, p2.x, 1e-3f)
            assertEquals("Tilt invariant Y mismatch at index $i", p1.y, p2.y, 1e-3f)
            assertEquals("Tilt invariant Z mismatch at index $i", p1.z, p2.z, 1e-3f)
        }
    }

    /**
     * TEST 6: Invalid landmark count rejection.
     */
    @Test
    fun testInvalidLandmarkCount_rejected() {
        val emptyVisionResult = VisionFrameResult(
            timestampMs = 1000L,
            hands = emptyList(),
            inferenceLatencyMs = 10L,
            inputImageWidth = 640,
            inputImageHeight = 480
        )

        val result = normalizer.normalize(emptyVisionResult)
        assertNull("Empty vision result should normalize to null", result)
    }
}
