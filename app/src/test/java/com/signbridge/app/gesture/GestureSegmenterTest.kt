package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Unit test suite for [GestureSegmenter] event-driven segmentation state machine.
 */
class GestureSegmenterTest {

    private fun createSyntheticFrame(
        step: Int,
        displacement: Float
    ): NormalizedLandmarkFrame {
        val points = (0 until 21).map { i ->
            NormalizedLandmarkPoint(
                x = (i * 0.04f) + displacement,
                y = (i * 0.03f) - (displacement * 0.5f),
                z = (i * 0.01f)
            )
        }
        return NormalizedLandmarkFrame(
            timestampMs = step * 33L,
            handedness = "Right",
            landmarks = points,
            handScale = 1.0f,
            rawWristPosition = LandmarkPoint(0.5f, 0.5f, 0.0f)
        )
    }

    @Test
    fun testIdleStateIgnoresRestingHand() {
        val segmenter = GestureSegmenter()
        assertEquals(SegmenterState.IDLE, segmenter.state)

        // Feed 15 stationary frames (displacement delta ~0.005)
        for (i in 0 until 15) {
            val frame = createSyntheticFrame(i, i * 0.002f)
            val event = segmenter.processFrame(frame)
            assertTrue("Event should be progress", event is SegmentationEvent.Progress)
            assertEquals(SegmenterState.IDLE, segmenter.state)
        }
        assertEquals(SegmenterState.IDLE, segmenter.state)
    }

    @Test
    fun testHandEntrySingleSpikeIgnored() {
        val segmenter = GestureSegmenter()

        // Frame 0: Initial frame
        segmenter.processFrame(createSyntheticFrame(0, 0f))

        // Frame 1: Single fast entry spike (delta 0.15)
        val event1 = segmenter.processFrame(createSyntheticFrame(1, 0.15f))
        assertTrue(event1 is SegmentationEvent.Progress)
        assertEquals(SegmenterState.IDLE, segmenter.state) // 1 frame spike should not trigger CAPTURING

        // Frame 2: Hand immediately settles (delta 0.001)
        val event2 = segmenter.processFrame(createSyntheticFrame(2, 0.151f))
        assertTrue(event2 is SegmentationEvent.Progress)
        assertEquals(SegmenterState.IDLE, segmenter.state)
    }

    @Test
    fun testMotionStartTransitionOnConsecutiveVelocity() {
        val segmenter = GestureSegmenter()

        segmenter.processFrame(createSyntheticFrame(0, 0f))

        // Frame 1: High velocity (delta 0.08)
        val ev1 = segmenter.processFrame(createSyntheticFrame(1, 0.08f))
        assertEquals(SegmenterState.IDLE, segmenter.state)

        // Frame 2: Second consecutive high velocity frame (delta 0.08)
        val ev2 = segmenter.processFrame(createSyntheticFrame(2, 0.16f))
        assertEquals(SegmenterState.CAPTURING, segmenter.state)
        assertTrue(ev2 is SegmentationEvent.Progress)
        assertEquals(SegmenterState.CAPTURING, (ev2 as SegmentationEvent.Progress).state)
    }

    @Test
    fun testPauseToleranceDuringGesture() {
        val segmenter = GestureSegmenter(
            motionStartVelocityThreshold = 0.045f,
            motionEndVelocityThreshold = 0.028f,
            motionEndConsecutiveFrames = 6,
            pauseToleranceFrames = 4,
            minGestureFrames = 8
        )

        // 1. Initial idle frame
        segmenter.processFrame(createSyntheticFrame(0, 0f))

        // 2. Trigger motion (frames 1-4)
        for (i in 1..4) {
            segmenter.processFrame(createSyntheticFrame(i, i * 0.08f))
        }
        assertEquals(SegmenterState.CAPTURING, segmenter.state)

        // 3. Mid-gesture intentional pause for 3 frames (below motionEndVelocityThreshold)
        val currentDisp = 4 * 0.08f
        for (i in 5..7) {
            val ev = segmenter.processFrame(createSyntheticFrame(i, currentDisp + (i - 4) * 0.005f))
            // Must STILL be in CAPTURING because pause is only 3 frames (< 6 frames end condition)
            assertEquals(SegmenterState.CAPTURING, segmenter.state)
            assertTrue(ev is SegmentationEvent.Progress)
        }

        // 4. Motion resumes (frames 8-12)
        for (i in 8..12) {
            segmenter.processFrame(createSyntheticFrame(i, currentDisp + (i - 7) * 0.08f))
            assertEquals(SegmenterState.CAPTURING, segmenter.state)
        }
    }

    @Test
    fun testSustainedStillnessFinalizesAndTrimsGesture() {
        val segmenter = GestureSegmenter(
            motionStartVelocityThreshold = 0.045f,
            motionEndVelocityThreshold = 0.028f,
            motionEndConsecutiveFrames = 6,
            minGestureFrames = 8
        )

        segmenter.processFrame(createSyntheticFrame(0, 0f))

        // Perform 12 active gesture frames
        for (i in 1..12) {
            segmenter.processFrame(createSyntheticFrame(i, i * 0.09f))
        }
        assertEquals(SegmenterState.CAPTURING, segmenter.state)

        val endDisp = 12 * 0.09f
        var completedEvent: SegmentationEvent.Completed? = null

        // Perform 6 stationary settling frames
        for (i in 13..18) {
            val ev = segmenter.processFrame(createSyntheticFrame(i, endDisp + (i - 12) * 0.002f))
            if (ev is SegmentationEvent.Completed) {
                completedEvent = ev
            }
        }

        assertNotNull("Gesture should complete after 6 still frames", completedEvent)
        assertEquals(SegmenterState.IDLE, segmenter.state)
        assertTrue("Sequence should be valid and ready", completedEvent!!.sequence.isReady)
        assertTrue("Trimmed frames (${completedEvent.trimmedFrameCount}) should exclude trailing stillness", completedEvent.trimmedFrameCount >= 8)
        assertTrue("Raw frame count (${completedEvent.rawFrameCount}) should exceed trimmed count", completedEvent.rawFrameCount > completedEvent.trimmedFrameCount)
    }

    @Test
    fun testRejectsNoiseTwitchShorterThanMinFrames() {
        val segmenter = GestureSegmenter(
            motionStartVelocityThreshold = 0.045f,
            motionEndVelocityThreshold = 0.028f,
            motionEndConsecutiveFrames = 4,
            minGestureFrames = 8
        )

        segmenter.processFrame(createSyntheticFrame(0, 0f))

        // Start motion for only 2 frames (too short)
        segmenter.processFrame(createSyntheticFrame(1, 0.09f))
        segmenter.processFrame(createSyntheticFrame(2, 0.18f))
        assertEquals(SegmenterState.CAPTURING, segmenter.state)

        var rejectedEvent: SegmentationEvent.Rejected? = null

        // Stop immediately
        for (i in 3..7) {
            val ev = segmenter.processFrame(createSyntheticFrame(i, 0.181f))
            if (ev is SegmentationEvent.Rejected) {
                rejectedEvent = ev
            }
        }

        assertNotNull("Noise twitch should be rejected", rejectedEvent)
        assertTrue(rejectedEvent!!.reason.contains("too short"))
        assertEquals(SegmenterState.IDLE, segmenter.state)
    }

    @Test
    fun testVariableLengthDTWMatching() {
        val matcher = PrototypeMatcher()

        // 30-frame prototype for HELP
        val helpFrames = (0 until 30).map { step ->
            val progress = step.toFloat() / 29f
            val points = (0 until 21).map { i ->
                NormalizedLandmarkPoint(
                    x = (i * 0.04f) + (progress * 0.35f),
                    y = (i * 0.03f) - (progress * 0.35f),
                    z = (i * 0.01f)
                )
            }
            NormalizedLandmarkFrame(step * 33L, "Right", points, 1.0f, LandmarkPoint(0.5f, 0.5f, 0f))
        }
        val helpProto = GesturePrototype("HELP_1", "HELP", TemporalSequence(helpFrames, 30, true))
        matcher.addPrototype(helpProto)

        // 18-frame faster live execution of HELP
        val fasterFrames = (0 until 18).map { step ->
            val progress = step.toFloat() / 17f
            val points = (0 until 21).map { i ->
                NormalizedLandmarkPoint(
                    x = (i * 0.04f) + (progress * 0.35f) + 0.01f,
                    y = (i * 0.03f) - (progress * 0.35f) + 0.01f,
                    z = (i * 0.01f)
                )
            }
            NormalizedLandmarkFrame(step * 33L, "Right", points, 1.0f, LandmarkPoint(0.5f, 0.5f, 0f))
        }
        val fasterSeq = TemporalSequence(fasterFrames, 18, true)

        val result = matcher.match(fasterSeq, threshold = 0.28)
        assertEquals(MatchStatus.MATCH, result.status)
        assertEquals("HELP", result.recognizedLabel)
        assertTrue("Variable-length DTW distance should be small (<0.20)", result.nearestDistance < 0.20)
    }
}
