package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalBufferTest {

    private fun createDummyNormalizedFrame(timestampMs: Long): NormalizedLandmarkFrame {
        val points = (0 until 21).map { i ->
            NormalizedLandmarkPoint(x = i * 0.05f, y = i * 0.05f, z = 0.0f)
        }
        return NormalizedLandmarkFrame(
            timestampMs = timestampMs,
            handedness = "Right",
            landmarks = points,
            handScale = 0.25f,
            rawWristPosition = LandmarkPoint(0.5f, 0.5f, 0.0f)
        )
    }

    /**
     * TEST 5: Temporal buffer initially empty.
     */
    @Test
    fun testBufferInitiallyEmpty() {
        val buffer = TemporalBuffer(windowSize = 30)

        assertEquals(0, buffer.size)
        assertFalse(buffer.isReady)
        assertEquals(SequenceStatus.EMPTY, buffer.status)

        val snapshot = buffer.getSnapshot()
        assertEquals(0, snapshot.frameCount)
        assertFalse(snapshot.isReady)
    }

    /**
     * TEST 6: Temporal buffer fills sequentially until capacity.
     */
    @Test
    fun testBufferFillsToCapacity() {
        val buffer = TemporalBuffer(windowSize = 30)

        for (i in 1..29) {
            val status = buffer.addFrame(createDummyNormalizedFrame(i * 33L))
            assertEquals(SequenceStatus.FILLING, status)
            assertEquals(i, buffer.size)
            assertFalse(buffer.isReady)
        }

        // Add 30th frame
        val status30 = buffer.addFrame(createDummyNormalizedFrame(30 * 33L))
        assertEquals(SequenceStatus.READY, status30)
        assertEquals(30, buffer.size)
        assertTrue(buffer.isReady)
    }

    /**
     * TEST 7: Rolling behavior when frame 31 arrives.
     * Oldest frame (frame 1) is discarded; buffer remains at size 30.
     */
    @Test
    fun testRollingBufferDiscardsOldest() {
        val buffer = TemporalBuffer(windowSize = 30)

        // Add frames 1 to 30
        for (i in 1..30) {
            buffer.addFrame(createDummyNormalizedFrame(timestampMs = i * 100L))
        }
        assertEquals(30, buffer.size)

        // Add frame 31 (timestamp = 3100L)
        val status31 = buffer.addFrame(createDummyNormalizedFrame(timestampMs = 3100L))
        assertEquals(SequenceStatus.READY, status31)
        assertEquals(30, buffer.size)

        val snapshot = buffer.getSnapshot()
        assertEquals(30, snapshot.frameCount)
        // Oldest frame in buffer should now be frame 2 (timestamp = 200L)
        assertEquals(200L, snapshot.frames.first().timestampMs)
        // Newest frame in buffer should be frame 31 (timestamp = 3100L)
        assertEquals(3100L, snapshot.frames.last().timestampMs)
    }

    /**
     * TEST 8: Sequence snapshot immutability.
     */
    @Test
    fun testSnapshotImmutability() {
        val buffer = TemporalBuffer(windowSize = 5)
        for (i in 1..5) {
            buffer.addFrame(createDummyNormalizedFrame(i * 100L))
        }

        val snapshot = buffer.getSnapshot()
        assertEquals(5, snapshot.frameCount)

        // Adding more frames to the buffer should not mutate the previous snapshot
        buffer.addFrame(createDummyNormalizedFrame(600L))
        buffer.addFrame(createDummyNormalizedFrame(700L))

        assertEquals(5, snapshot.frameCount)
        assertEquals(100L, snapshot.frames.first().timestampMs)
    }

    /**
     * TEST 9: Invalid / null frame handling.
     * Null frames must not enter the temporal buffer or corrupt frame count.
     */
    @Test
    fun testNullFrameRejected() {
        val buffer = TemporalBuffer(windowSize = 10)
        buffer.addFrame(createDummyNormalizedFrame(100L))
        buffer.addFrame(createDummyNormalizedFrame(200L))

        assertEquals(2, buffer.size)

        // Add null frame (hand missing / lost)
        val status = buffer.addFrame(null)

        assertEquals(2, buffer.size)
        assertEquals(SequenceStatus.FILLING, status)
    }

    /**
     * TEST 10: Timestamp ordering.
     * Frames in returned sequence must remain in strict chronological order.
     */
    @Test
    fun testTimestampChronologicalOrdering() {
        val buffer = TemporalBuffer(windowSize = 10)
        for (i in 1..15) {
            buffer.addFrame(createDummyNormalizedFrame(timestampMs = i * 50L))
        }

        val snapshot = buffer.getSnapshot()
        val timestamps = snapshot.frames.map { it.timestampMs }

        for (i in 0 until timestamps.size - 1) {
            assertTrue("Frame $i timestamp must be < Frame ${i+1}", timestamps[i] < timestamps[i + 1])
        }
    }
}
