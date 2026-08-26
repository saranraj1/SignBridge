package com.signbridge.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkDataTest {

    @Test
    fun testEmptyVisionFrameResult() {
        val result = VisionFrameResult(
            timestampMs = 1000L,
            hands = emptyList(),
            inferenceLatencyMs = 15L,
            inputImageWidth = 640,
            inputImageHeight = 480
        )

        assertFalse(result.hasHands)
        assertEquals(0, result.totalLandmarksCount)
        assertEquals(15L, result.inferenceLatencyMs)
    }

    @Test
    fun testSingleHandVisionFrameResult() {
        val points = (0 until 21).map { i ->
            LandmarkPoint(x = i * 0.04f, y = i * 0.04f, z = 0.0f)
        }

        val hand = HandLandmarkData(
            handedness = "Right",
            score = 0.98f,
            landmarks = points
        )

        val result = VisionFrameResult(
            timestampMs = 2000L,
            hands = listOf(hand),
            inferenceLatencyMs = 18L,
            inputImageWidth = 1080,
            inputImageHeight = 1920
        )

        assertTrue(result.hasHands)
        assertEquals(1, result.hands.size)
        assertEquals(21, result.totalLandmarksCount)
        assertEquals("Right", result.hands[0].handedness)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidLandmarksCountThrows() {
        // Hand with only 10 landmarks should fail validation
        val invalidPoints = (0 until 10).map { i ->
            LandmarkPoint(x = 0.1f, y = 0.1f, z = 0.0f)
        }

        HandLandmarkData(
            handedness = "Left",
            score = 0.85f,
            landmarks = invalidPoints
        )
    }
}
