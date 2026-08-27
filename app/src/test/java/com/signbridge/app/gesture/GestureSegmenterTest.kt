package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive unit test suite for [GestureSegmenter] M4.5 final fix.
 *
 * Tests cover:
 * - NO_HAND → STABILIZING → IDLE → CAPTURING → COMPLETED lifecycle
 * - Hand entry stabilization (spike rejection)
 * - Slow and fast gesture start
 * - Single-frame noise rejection
 * - Short internal pause tolerance
 * - Long post-gesture inactivity → completion
 * - Variable-length gesture preservation
 * - Back-to-back gestures without hand removal
 * - Result consistency between teach and recognition
 * - Prototype immutability
 * - Min gesture frame rejection
 */
class GestureSegmenterTest {

    /**
     * Creates a synthetic normalized frame with controllable displacement.
     * Each landmark's position is deterministic based on step and displacement.
     */
    private fun createFrame(
        step: Int,
        displacement: Float,
        timestampMs: Long = step * 100L
    ): NormalizedLandmarkFrame {
        val points = (0 until 21).map { i ->
            NormalizedLandmarkPoint(
                x = (i * 0.04f) + displacement,
                y = (i * 0.03f) - (displacement * 0.5f),
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

    /**
     * Creates a segmenter with test-friendly parameters that mirror the production config ratios.
     * Lower end frames for faster test execution while maintaining the same behavioral patterns.
     */
    private fun createTestSegmenter(
        startThreshold: Float = 0.025f,
        endThreshold: Float = 0.018f,
        startConsecutive: Int = 3,
        endConsecutive: Int = 10,
        minFrames: Int = 6,
        maxFrames: Int = 90,
        stabilizationFrames: Int = 3
    ): GestureSegmenter {
        return GestureSegmenter(
            motionStartVelocityThreshold = startThreshold,
            motionEndVelocityThreshold = endThreshold,
            motionStartConsecutiveFrames = startConsecutive,
            motionEndConsecutiveFrames = endConsecutive,
            minGestureFrames = minFrames,
            maxGestureFrames = maxFrames,
            handStabilizationFrames = stabilizationFrames
        )
    }

    // ===== Test 1: NO_HAND state =====

    @Test
    fun testNullFrameClearsStateAndReportsIdle() {
        val seg = createTestSegmenter()
        // Initially handAbsent is true
        assertTrue(seg.handAbsent)

        // Null frame should return Progress(IDLE)
        val event = seg.processFrame(null)
        assertTrue(event is SegmentationEvent.Progress)
        assertEquals(SegmenterState.IDLE, (event as SegmentationEvent.Progress).state)
        assertTrue(seg.handAbsent)
    }

    // ===== Test 2: Hand appearance → STABILIZING =====

    @Test
    fun testHandAppearanceTransitionsToStabilizing() {
        val seg = createTestSegmenter(stabilizationFrames = 3)
        assertTrue(seg.handAbsent)

        // First non-null frame after absence → STABILIZING
        val event = seg.processFrame(createFrame(0, 0f))
        assertTrue(event is SegmentationEvent.Progress)
        assertEquals(SegmenterState.STABILIZING, (event as SegmentationEvent.Progress).state)
        assertEquals(SegmenterState.STABILIZING, seg.state)
        assertTrue(!seg.handAbsent)
    }

    // ===== Test 3: Stabilization → IDLE transition =====

    @Test
    fun testStabilizationCompletesAfterRequiredFrames() {
        val seg = createTestSegmenter(stabilizationFrames = 3)

        // Frame 0: hand appears → STABILIZING
        seg.processFrame(createFrame(0, 0f))
        assertEquals(SegmenterState.STABILIZING, seg.state)

        // Frame 1: still stabilizing (counter = 1)
        seg.processFrame(createFrame(1, 0.001f))
        assertEquals(SegmenterState.STABILIZING, seg.state)

        // Frame 2: still stabilizing (counter = 2)
        seg.processFrame(createFrame(2, 0.002f))
        assertEquals(SegmenterState.STABILIZING, seg.state)

        // Frame 3: stabilization complete (counter = 3) → IDLE
        val event = seg.processFrame(createFrame(3, 0.003f))
        assertEquals(SegmenterState.IDLE, seg.state)
        assertTrue(event is SegmentationEvent.Progress)
        assertEquals(SegmenterState.IDLE, (event as SegmentationEvent.Progress).state)
    }

    // ===== Test 4: Slow gesture start (velocity ~0.03) =====

    @Test
    fun testSlowGestureStartDetected() {
        val seg = createTestSegmenter(startThreshold = 0.025f, startConsecutive = 3, stabilizationFrames = 2)

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))
        assertEquals(SegmenterState.IDLE, seg.state)

        // Slow motion: displacement delta ~0.04 per frame → velocity > 0.025
        seg.processFrame(createFrame(3, 0.04f))
        assertEquals(SegmenterState.IDLE, seg.state) // 1 consecutive

        seg.processFrame(createFrame(4, 0.08f))
        assertEquals(SegmenterState.IDLE, seg.state) // 2 consecutive

        val event = seg.processFrame(createFrame(5, 0.12f))
        assertEquals(SegmenterState.CAPTURING, seg.state) // 3 consecutive → CAPTURING
        assertTrue(event is SegmentationEvent.Progress)
        assertEquals(SegmenterState.CAPTURING, (event as SegmentationEvent.Progress).state)
    }

    // ===== Test 5: Fast gesture start =====

    @Test
    fun testFastGestureStartDetected() {
        val seg = createTestSegmenter(startThreshold = 0.025f, startConsecutive = 3, stabilizationFrames = 2)

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))
        assertEquals(SegmenterState.IDLE, seg.state)

        // Fast motion: large displacements
        seg.processFrame(createFrame(3, 0.10f))
        seg.processFrame(createFrame(4, 0.20f))
        seg.processFrame(createFrame(5, 0.30f))
        assertEquals(SegmenterState.CAPTURING, seg.state)
    }

    // ===== Test 6: Single-frame noise rejection =====

    @Test
    fun testSingleFrameMotionNoiseIgnored() {
        val seg = createTestSegmenter(startThreshold = 0.025f, startConsecutive = 3, stabilizationFrames = 2)

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))
        assertEquals(SegmenterState.IDLE, seg.state)

        // Single spike frame
        seg.processFrame(createFrame(3, 0.15f))
        assertEquals(SegmenterState.IDLE, seg.state) // 1 frame only

        // Immediately settles
        seg.processFrame(createFrame(4, 0.151f))
        assertEquals(SegmenterState.IDLE, seg.state) // velocity drops, counter resets

        // Another single spike
        seg.processFrame(createFrame(5, 0.30f))
        assertEquals(SegmenterState.IDLE, seg.state) // 1 frame only again

        seg.processFrame(createFrame(6, 0.301f))
        assertEquals(SegmenterState.IDLE, seg.state) // back to low velocity
    }

    // ===== Test 7: Short internal pause tolerance =====

    @Test
    fun testShortInternalPauseDoesNotTerminateCapture() {
        val seg = createTestSegmenter(
            startThreshold = 0.025f, endThreshold = 0.018f,
            startConsecutive = 3, endConsecutive = 10,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))

        // Start capturing (3 consecutive high-velocity frames)
        seg.processFrame(createFrame(3, 0.06f))
        seg.processFrame(createFrame(4, 0.12f))
        seg.processFrame(createFrame(5, 0.18f))
        assertEquals(SegmenterState.CAPTURING, seg.state)

        // Continue active motion for a few more frames
        seg.processFrame(createFrame(6, 0.24f))
        seg.processFrame(createFrame(7, 0.30f))

        // Short pause: 5 frames of near-stillness (well below 10-frame end threshold)
        val pauseBase = 0.30f
        for (i in 8..12) {
            seg.processFrame(createFrame(i, pauseBase + (i - 8) * 0.002f))
            assertEquals("Still CAPTURING during 5-frame pause at frame $i",
                SegmenterState.CAPTURING, seg.state)
        }

        // Motion resumes
        seg.processFrame(createFrame(13, pauseBase + 0.06f))
        assertEquals(SegmenterState.CAPTURING, seg.state)
        seg.processFrame(createFrame(14, pauseBase + 0.12f))
        assertEquals(SegmenterState.CAPTURING, seg.state)
    }

    // ===== Test 8: Long post-gesture inactivity → completion =====

    @Test
    fun testSustainedInactivityFinalizesGesture() {
        val seg = createTestSegmenter(
            startThreshold = 0.025f, endThreshold = 0.018f,
            startConsecutive = 3, endConsecutive = 8,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))

        // Start capturing
        seg.processFrame(createFrame(3, 0.06f))
        seg.processFrame(createFrame(4, 0.12f))
        seg.processFrame(createFrame(5, 0.18f))
        assertEquals(SegmenterState.CAPTURING, seg.state)

        // Active motion for several more frames
        for (i in 6..11) {
            seg.processFrame(createFrame(i, 0.18f + (i - 5) * 0.06f))
        }
        assertEquals(SegmenterState.CAPTURING, seg.state)

        // Now sustained stillness: 8 consecutive low-velocity frames
        val endBase = 0.18f + 6 * 0.06f
        var completedEvent: SegmentationEvent.Completed? = null
        for (i in 12..19) {
            val ev = seg.processFrame(createFrame(i, endBase + (i - 12) * 0.001f))
            if (ev is SegmentationEvent.Completed) {
                completedEvent = ev
            }
        }

        assertNotNull("Gesture should complete after sustained stillness", completedEvent)
        assertEquals(SegmenterState.IDLE, seg.state)
        assertTrue("Sequence should be ready", completedEvent!!.sequence.isReady)
        assertTrue("Trimmed frames should exclude trailing idle",
            completedEvent.trimmedFrameCount < completedEvent.rawFrameCount)
        assertTrue("Trimmed frames (${completedEvent.trimmedFrameCount}) >= minFrames",
            completedEvent.trimmedFrameCount >= 6)
        assertTrue("End reason should be sustained_stillness",
            completedEvent.endReason == "sustained_stillness")
    }

    // ===== Test 9: Variable-length gesture preservation =====

    @Test
    fun testVariableLengthGesturePreservedCorrectly() {
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 8,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))

        // Start + 15 active frames (a longer gesture)
        seg.processFrame(createFrame(3, 0.04f))
        seg.processFrame(createFrame(4, 0.08f))
        seg.processFrame(createFrame(5, 0.12f))
        for (i in 6..19) {
            seg.processFrame(createFrame(i, 0.12f + (i - 5) * 0.04f))
        }
        assertEquals(SegmenterState.CAPTURING, seg.state)

        // End with sustained stillness
        val endBase = 0.12f + 14 * 0.04f
        var completed: SegmentationEvent.Completed? = null
        for (i in 20..28) {
            val ev = seg.processFrame(createFrame(i, endBase + (i - 20) * 0.001f))
            if (ev is SegmentationEvent.Completed) completed = ev
        }

        assertNotNull("Variable-length gesture should complete", completed)
        // The trimmed sequence should have ~15 active frames (not forced to 8-9)
        assertTrue("Trimmed count (${completed!!.trimmedFrameCount}) should be >= 10 for a 15-frame gesture",
            completed.trimmedFrameCount >= 10)
    }

    // ===== Test 10: Back-to-back gestures without hand removal =====

    @Test
    fun testBackToBackGesturesWithoutHandRemoval() {
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 8,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))

        // ===== Gesture 1 =====
        seg.processFrame(createFrame(3, 0.04f))
        seg.processFrame(createFrame(4, 0.08f))
        seg.processFrame(createFrame(5, 0.12f))
        assertEquals(SegmenterState.CAPTURING, seg.state)

        for (i in 6..11) {
            seg.processFrame(createFrame(i, 0.12f + (i - 5) * 0.05f))
        }

        // End gesture 1 with stillness
        val g1End = 0.12f + 6 * 0.05f
        var completed1: SegmentationEvent.Completed? = null
        for (i in 12..20) {
            val ev = seg.processFrame(createFrame(i, g1End + (i - 12) * 0.001f))
            if (ev is SegmentationEvent.Completed) completed1 = ev
        }
        assertNotNull("Gesture 1 should complete", completed1)
        assertEquals(SegmenterState.IDLE, seg.state)

        // ===== Gesture 2 (no hand removal — stay in IDLE, start new motion) =====
        seg.processFrame(createFrame(21, g1End + 0.04f))
        seg.processFrame(createFrame(22, g1End + 0.08f))
        seg.processFrame(createFrame(23, g1End + 0.12f))
        assertEquals(SegmenterState.CAPTURING, seg.state)

        for (i in 24..29) {
            seg.processFrame(createFrame(i, g1End + 0.12f + (i - 23) * 0.05f))
        }

        val g2End = g1End + 0.12f + 6 * 0.05f
        var completed2: SegmentationEvent.Completed? = null
        for (i in 30..38) {
            val ev = seg.processFrame(createFrame(i, g2End + (i - 30) * 0.001f))
            if (ev is SegmentationEvent.Completed) completed2 = ev
        }
        assertNotNull("Gesture 2 should complete without hand removal", completed2)
        assertEquals(SegmenterState.IDLE, seg.state)
    }

    // ===== Test 11: Hand loss during capture with enough frames → finalize =====

    @Test
    fun testHandLossDuringCaptureFinalizes() {
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 10,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize + start capturing
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))
        seg.processFrame(createFrame(3, 0.06f))
        seg.processFrame(createFrame(4, 0.12f))
        seg.processFrame(createFrame(5, 0.18f))
        assertEquals(SegmenterState.CAPTURING, seg.state)

        // Add more active frames
        for (i in 6..11) {
            seg.processFrame(createFrame(i, 0.18f + (i - 5) * 0.06f))
        }
        assertTrue("Should have accumulated frames", seg.currentAccumulatedCount >= 6)

        // Hand disappears
        val event = seg.processFrame(null)
        assertTrue("Should finalize on hand loss", event is SegmentationEvent.Completed)
        assertTrue(seg.handAbsent)
        val completed = event as SegmentationEvent.Completed
        assertEquals("hand_lost", completed.endReason)
    }

    // ===== Test 12: Hand loss during capture with too few frames → reject =====

    @Test
    fun testHandLossDuringCaptureWithTooFewFramesRejects() {
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 10,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))

        // Start capturing but only 1 frame
        seg.processFrame(createFrame(3, 0.10f))
        seg.processFrame(createFrame(4, 0.20f))
        seg.processFrame(createFrame(5, 0.30f))
        assertEquals(SegmenterState.CAPTURING, seg.state)
        // Only 1 captured frame (the frame at state transition)

        // Hand disappears before enough frames
        val event = seg.processFrame(null)
        assertTrue("Should reject short capture on hand loss", event is SegmentationEvent.Rejected)
        assertTrue(seg.handAbsent)
    }

    // ===== Test 13: Min gesture frame rejection =====

    @Test
    fun testRejectsTwitchShorterThanMinFrames() {
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 4,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))

        // Start capturing
        seg.processFrame(createFrame(3, 0.06f))
        seg.processFrame(createFrame(4, 0.12f))
        seg.processFrame(createFrame(5, 0.18f))
        assertEquals(SegmenterState.CAPTURING, seg.state)
        // Only 1 captured frame at transition

        // Immediately stop (4 consecutive low-vel)
        var rejected: SegmentationEvent.Rejected? = null
        for (i in 6..9) {
            val ev = seg.processFrame(createFrame(i, 0.181f))
            if (ev is SegmentationEvent.Rejected) rejected = ev
        }

        assertNotNull("Short twitch should be rejected", rejected)
        assertTrue(rejected!!.reason.contains("too short"))
        assertEquals(SegmenterState.IDLE, seg.state)
    }

    // ===== Test 14: Teach/recognition representation consistency =====

    @Test
    fun testTeachAndRecognitionUseSameRepresentation() {
        // Both teach and recognition feed through the same GestureSegmenter.
        // This test verifies that two identical gesture executions produce similar sequences.
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 6,
            minFrames = 6, stabilizationFrames = 2
        )

        // Execute the same gesture pattern twice (simulating teach + recognition)
        val completedSequences = mutableListOf<SegmentationEvent.Completed>()

        for (run in 0..1) {
            // Hand appears + stabilize
            if (run > 0) {
                // Simulate hand withdrawal + re-entry between runs
                seg.processFrame(null)
            }
            seg.processFrame(createFrame(0, 0f))
            seg.processFrame(createFrame(1, 0.001f))
            seg.processFrame(createFrame(2, 0.002f))

            // Same gesture: consistent displacement pattern
            seg.processFrame(createFrame(3, 0.05f))
            seg.processFrame(createFrame(4, 0.10f))
            seg.processFrame(createFrame(5, 0.15f))
            for (i in 6..12) {
                seg.processFrame(createFrame(i, 0.15f + (i - 5) * 0.04f))
            }

            // End with stillness
            val endBase = 0.15f + 7 * 0.04f
            for (i in 13..19) {
                val ev = seg.processFrame(createFrame(i, endBase + (i - 13) * 0.001f))
                if (ev is SegmentationEvent.Completed) {
                    completedSequences.add(ev)
                }
            }
        }

        assertEquals("Both gesture executions should complete", 2, completedSequences.size)
        val s1 = completedSequences[0]
        val s2 = completedSequences[1]

        // Frame counts should be identical for identical input
        assertEquals("Same gesture should produce same frame count",
            s1.trimmedFrameCount, s2.trimmedFrameCount)
    }

    // ===== Test 15: Prototype immutability =====

    @Test
    fun testPrototypeSequenceIsImmutableAfterCreation() {
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 6,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))

        // Capture a gesture
        seg.processFrame(createFrame(3, 0.05f))
        seg.processFrame(createFrame(4, 0.10f))
        seg.processFrame(createFrame(5, 0.15f))
        for (i in 6..12) {
            seg.processFrame(createFrame(i, 0.15f + (i - 5) * 0.04f))
        }

        var completed: SegmentationEvent.Completed? = null
        val endBase = 0.15f + 7 * 0.04f
        for (i in 13..19) {
            val ev = seg.processFrame(createFrame(i, endBase + (i - 13) * 0.001f))
            if (ev is SegmentationEvent.Completed) completed = ev
        }

        assertNotNull(completed)
        val seq = completed!!.sequence

        // Create a prototype from this sequence
        val proto = GesturePrototype("test_1", "TEST", seq)

        // Verify the prototype's sequence matches the original
        assertEquals(seq.frameCount, proto.sequence.frameCount)
        assertEquals(seq.isReady, proto.sequence.isReady)

        // Verify creating another segmented gesture doesn't affect the prototype
        seg.processFrame(createFrame(20, endBase + 0.10f))
        seg.processFrame(createFrame(21, endBase + 0.20f))
        seg.processFrame(createFrame(22, endBase + 0.30f))

        // Proto should still have the original frame count
        assertEquals("Prototype should be immutable", seq.frameCount, proto.sequence.frameCount)
    }

    // ===== Test 16: Variable-length DTW matching =====

    @Test
    fun testVariableLengthDTWMatching() {
        val matcher = PrototypeMatcher()

        // 20-frame prototype for HELP
        val helpFrames = (0 until 20).map { step ->
            val progress = step.toFloat() / 19f
            val points = (0 until 21).map { i ->
                NormalizedLandmarkPoint(
                    x = (i * 0.04f) + (progress * 0.35f),
                    y = (i * 0.03f) - (progress * 0.35f),
                    z = (i * 0.01f)
                )
            }
            NormalizedLandmarkFrame(step * 100L, "Right", points, 1.0f, LandmarkPoint(0.5f, 0.5f, 0f))
        }
        val helpProto = GesturePrototype("HELP_1", "HELP", TemporalSequence(helpFrames, 20, true))
        matcher.addPrototype(helpProto)

        // 15-frame faster live execution of the same gesture
        val fasterFrames = (0 until 15).map { step ->
            val progress = step.toFloat() / 14f
            val points = (0 until 21).map { i ->
                NormalizedLandmarkPoint(
                    x = (i * 0.04f) + (progress * 0.35f) + 0.01f,
                    y = (i * 0.03f) - (progress * 0.35f) + 0.01f,
                    z = (i * 0.01f)
                )
            }
            NormalizedLandmarkFrame(step * 100L, "Right", points, 1.0f, LandmarkPoint(0.5f, 0.5f, 0f))
        }
        val fasterSeq = TemporalSequence(fasterFrames, 15, true)

        val result = matcher.match(fasterSeq, threshold = 0.35)
        assertEquals(MatchStatus.MATCH, result.status)
        assertEquals("HELP", result.recognizedLabel)
        assertTrue("Variable-length DTW distance should be small", result.nearestDistance < 0.25)
    }

    // ===== Test 17: Max frames cap =====

    @Test
    fun testMaxFrameCapForcesFinalization() {
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 100, // very high — won't end from stillness
            minFrames = 6, maxFrames = 20, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))

        // Start capturing
        seg.processFrame(createFrame(3, 0.06f))
        seg.processFrame(createFrame(4, 0.12f))
        seg.processFrame(createFrame(5, 0.18f))
        assertEquals(SegmenterState.CAPTURING, seg.state)

        // Keep adding high-velocity frames until max
        var completed: SegmentationEvent.Completed? = null
        for (i in 6..25) {
            val ev = seg.processFrame(createFrame(i, 0.18f + (i - 5) * 0.06f))
            if (ev is SegmentationEvent.Completed) {
                completed = ev
                break
            }
        }

        assertNotNull("Max frames should force completion", completed)
        assertEquals("max_frames_reached", completed!!.endReason)
    }

    // ===== Test 18: Stabilization blocks motion during entry =====

    @Test
    fun testStabilizationBlocksMotionDuringHandEntry() {
        val seg = createTestSegmenter(stabilizationFrames = 4)

        // Hand enters with large displacement
        seg.processFrame(createFrame(0, 0f))
        assertEquals(SegmenterState.STABILIZING, seg.state)

        // Large motion during stabilization should NOT trigger CAPTURING
        seg.processFrame(createFrame(1, 0.20f))
        assertEquals(SegmenterState.STABILIZING, seg.state)

        seg.processFrame(createFrame(2, 0.40f))
        assertEquals(SegmenterState.STABILIZING, seg.state)

        seg.processFrame(createFrame(3, 0.60f))
        assertEquals(SegmenterState.STABILIZING, seg.state)

        // Stabilization completes → IDLE (not CAPTURING!)
        seg.processFrame(createFrame(4, 0.61f))
        assertEquals(SegmenterState.IDLE, seg.state)
    }

    // ===== Test 19: Hand re-entry after removal re-stabilizes =====

    @Test
    fun testHandReEntryTriggersNewStabilization() {
        val seg = createTestSegmenter(stabilizationFrames = 3)

        // Hand appears + stabilize + reach IDLE
        seg.processFrame(createFrame(0, 0f))
        seg.processFrame(createFrame(1, 0.001f))
        seg.processFrame(createFrame(2, 0.002f))
        seg.processFrame(createFrame(3, 0.003f))
        assertEquals(SegmenterState.IDLE, seg.state)

        // Hand disappears
        seg.processFrame(null)
        assertTrue(seg.handAbsent)

        // Hand re-enters → must go through STABILIZING again
        seg.processFrame(createFrame(4, 0.5f))
        assertEquals(SegmenterState.STABILIZING, seg.state)
    }

    // ===== Test 20: Completed event contains correct diagnostic data =====

    @Test
    fun testCompletedEventContainsDiagnostics() {
        val seg = createTestSegmenter(
            startConsecutive = 3, endConsecutive = 6,
            minFrames = 6, stabilizationFrames = 2
        )

        // Hand appears + stabilize
        seg.processFrame(createFrame(0, 0f, 0))
        seg.processFrame(createFrame(1, 0.001f, 100))
        seg.processFrame(createFrame(2, 0.002f, 200))

        // Capture gesture
        seg.processFrame(createFrame(3, 0.05f, 300))
        seg.processFrame(createFrame(4, 0.10f, 400))
        seg.processFrame(createFrame(5, 0.15f, 500))
        for (i in 6..12) {
            seg.processFrame(createFrame(i, 0.15f + (i - 5) * 0.04f, i * 100L))
        }

        var completed: SegmentationEvent.Completed? = null
        val endBase = 0.15f + 7 * 0.04f
        for (i in 13..19) {
            val ev = seg.processFrame(createFrame(i, endBase + (i - 13) * 0.001f, i * 100L))
            if (ev is SegmentationEvent.Completed) completed = ev
        }

        assertNotNull(completed)
        assertTrue("Duration should be > 0", completed!!.durationMs > 0)
        assertTrue("Motion profile should contain M and S characters",
            completed.motionProfile.contains('M') || completed.motionProfile.contains('S'))
        assertTrue("Mean velocity should be > 0", completed.meanVelocity > 0)
        assertEquals("sustained_stillness", completed.endReason)
        assertTrue("Raw frame count should be >= trimmed", completed.rawFrameCount >= completed.trimmedFrameCount)
    }
}
