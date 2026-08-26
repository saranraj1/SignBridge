package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeMatcherTest {

    private fun createFrame(offset: Float, timestampMs: Long): NormalizedLandmarkFrame {
        val points = (0 until 21).map { i ->
            NormalizedLandmarkPoint(
                x = (i * 0.05f + offset),
                y = (i * 0.03f + offset),
                z = (i * 0.01f)
            )
        }
        return NormalizedLandmarkFrame(
            timestampMs = timestampMs,
            handedness = "Right",
            landmarks = points,
            handScale = 1.0f,
            rawWristPosition = LandmarkPoint(0.5f, 0.5f, 0.0f)
        )
    }

    private fun createSequence(length: Int, offset: Float, stepMs: Long = 33L): TemporalSequence {
        val frames = (0 until length).map { i ->
            createFrame(offset = offset + (i * 0.005f), timestampMs = i * stepMs)
        }
        return TemporalSequence(frames = frames, windowSize = length, isReady = true)
    }

    /**
     * TEST 9: 1-NN Matching.
     * Given prototypes A, B, C; when live sequence is very close to B, B is selected as nearest match.
     */
    @Test
    fun testNearestNeighborSelection() {
        val matcher = PrototypeMatcher()

        val seqA = createSequence(length = 30, offset = 0.0f)
        val seqB = createSequence(length = 30, offset = 5.0f)
        val seqC = createSequence(length = 30, offset = 10.0f)

        matcher.addPrototype(GesturePrototype("proto_a", "GESTURE_A", seqA))
        matcher.addPrototype(GesturePrototype("proto_b", "GESTURE_B", seqB))
        matcher.addPrototype(GesturePrototype("proto_c", "GESTURE_C", seqC))

        // Live sequence very close to B (offset 5.05 vs 5.0)
        val liveQuery = createSequence(length = 30, offset = 5.05f)

        val result = matcher.match(liveQuery, threshold = 1.0)

        assertNotNull(result.bestMatch)
        assertEquals("proto_b", result.bestMatch?.id)
        assertEquals("GESTURE_B", result.recognizedLabel)
        assertEquals(MatchStatus.MATCH, result.status)
        assertTrue(result.isAccepted)
        assertTrue(result.nearestDistance < 1.0)
    }

    /**
     * TEST 10: Unknown threshold gating.
     * When nearest distance exceeds the configured threshold, match status is UNKNOWN.
     */
    @Test
    fun testDistanceExceedingThresholdReturnsUnknown() {
        val matcher = PrototypeMatcher()

        val protoSeq = createSequence(length = 30, offset = 0.0f)
        matcher.addPrototype(GesturePrototype("proto_hello", "HELLO", protoSeq))

        // Live sequence with large difference (offset 4.0)
        val randomGesture = createSequence(length = 30, offset = 4.0f)

        val result = matcher.match(randomGesture, threshold = 0.5)

        assertEquals(MatchStatus.UNKNOWN, result.status)
        assertFalse(result.isAccepted)
        assertEquals("UNKNOWN", result.recognizedLabel)
        assertTrue(result.nearestDistance > 0.5)
    }

    /**
     * TEST 11: Known threshold gating.
     * When nearest distance is below threshold, match status is MATCH.
     */
    @Test
    fun testDistanceBelowThresholdReturnsMatch() {
        val matcher = PrototypeMatcher()

        val protoSeq = createSequence(length = 30, offset = 1.0f)
        matcher.addPrototype(GesturePrototype("proto_help", "HELP", protoSeq))

        // Live sequence with small variation (offset 1.02)
        val similarGesture = createSequence(length = 30, offset = 1.02f)

        val result = matcher.match(similarGesture, threshold = 2.0)

        assertEquals(MatchStatus.MATCH, result.status)
        assertTrue(result.isAccepted)
        assertEquals("HELP", result.recognizedLabel)
    }

    /**
     * TEST 12: Chronological ordering and buffer readiness gating.
     * If sequence is not ready (< 5 frames), matcher returns SEQUENCE_NOT_READY.
     */
    @Test
    fun testSequenceNotReadyGating() {
        val matcher = PrototypeMatcher()
        matcher.addPrototype(GesturePrototype("p1", "P1", createSequence(30, 0.0f)))

        val shortSeq = createSequence(length = 3, offset = 0.0f) // Not ready
        val result = matcher.match(shortSeq)

        assertEquals(MatchStatus.SEQUENCE_NOT_READY, result.status)
        assertFalse(result.isAccepted)
        assertEquals("BUFFERING", result.recognizedLabel)
    }
}
