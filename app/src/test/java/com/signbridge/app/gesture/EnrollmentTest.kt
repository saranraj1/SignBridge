package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentTest {

    private fun createFrame(offset: Float, timestampMs: Long): NormalizedLandmarkFrame {
        val points = (0 until 21).map { i ->
            NormalizedLandmarkPoint(
                x = (i * 0.04f + offset),
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

    private fun createSequence(offset: Float, frameCount: Int = 30): TemporalSequence {
        val frames = (0 until frameCount).map { i ->
            createFrame(offset = offset + (i * 0.005f), timestampMs = i * 33L)
        }
        return TemporalSequence(frames = frames, windowSize = frameCount, isReady = true)
    }

    /**
     * TEST 1: Initial state is IDLE.
     */
    @Test
    fun testInitialStateIsIdle() {
        val controller = EnrollmentController()
        assertEquals(EnrollmentState.IDLE, controller.state)
        assertEquals(0, controller.capturedCount)
        assertFalse(controller.isTeaching)
    }

    /**
     * TEST 2: START_TEACHING transitions to TEACH_INTRO.
     */
    @Test
    fun testStartTeachingTransitionsToIntro() {
        val controller = EnrollmentController()
        controller.startTeaching()
        assertEquals(EnrollmentState.TEACH_INTRO, controller.state)
        assertTrue(controller.isTeaching)
    }

    /**
     * TEST 3: First valid sequence creates Sample 1.
     */
    @Test
    fun testFirstValidSequenceCapture() {
        val controller = EnrollmentController()
        controller.startTeaching()
        controller.startRecordingCurrentSample()
        assertEquals(EnrollmentState.RECORDING_1, controller.state)

        val seq1 = createSequence(offset = 0.1f)
        val captured = controller.processFrame(hasHands = true, bufferSnapshot = seq1)

        assertTrue(captured)
        assertEquals(EnrollmentState.CAPTURED_1, controller.state)
        assertEquals(1, controller.capturedCount)
    }

    /**
     * TEST 4: Second valid sequence creates Sample 2.
     */
    @Test
    fun testSecondValidSequenceCapture() {
        val controller = EnrollmentController()
        controller.startTeaching()
        controller.startRecordingCurrentSample()
        controller.processFrame(hasHands = true, bufferSnapshot = createSequence(0.1f))

        controller.startRecordingCurrentSample()
        assertEquals(EnrollmentState.RECORDING_2, controller.state)

        val captured = controller.processFrame(hasHands = true, bufferSnapshot = createSequence(0.2f))
        assertTrue(captured)
        assertEquals(EnrollmentState.CAPTURED_2, controller.state)
        assertEquals(2, controller.capturedCount)
    }

    /**
     * TEST 5: Third valid sequence creates Sample 3 and transitions to LABELING.
     */
    @Test
    fun testThirdValidSequenceCaptureTransitionsToLabeling() {
        val controller = EnrollmentController()
        controller.startTeaching()

        controller.startRecordingCurrentSample()
        controller.processFrame(hasHands = true, bufferSnapshot = createSequence(0.1f))

        controller.startRecordingCurrentSample()
        controller.processFrame(hasHands = true, bufferSnapshot = createSequence(0.2f))

        controller.startRecordingCurrentSample()
        assertEquals(EnrollmentState.RECORDING_3, controller.state)

        val captured = controller.processFrame(hasHands = true, bufferSnapshot = createSequence(0.3f))
        assertTrue(captured)
        assertEquals(EnrollmentState.LABELING, controller.state)
        assertEquals(3, controller.capturedCount)
    }

    /**
     * TEST 6: Incomplete enrollment cannot be saved.
     */
    @Test
    fun testIncompleteEnrollmentCannotBeSaved() {
        val controller = EnrollmentController()
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        controller.startTeaching()
        controller.recordSampleDirectly(createSequence(0.1f))
        controller.recordSampleDirectly(createSequence(0.2f))
        // Only 2 samples captured

        val result = controller.saveGesture("HELP", store, matcher)
        assertTrue(result.isFailure)
        assertEquals(0, store.profileCount)
    }

    /**
     * TEST 7: Empty label rejected.
     */
    @Test
    fun testEmptyLabelRejected() {
        val controller = EnrollmentController()
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        controller.startTeaching()
        for (i in 1..3) {
            controller.recordSampleDirectly(createSequence(i * 0.1f))
        }

        val result = controller.saveGesture("   ", store, matcher)
        assertTrue(result.isFailure)
    }

    /**
     * TEST 8: Valid label accepted.
     */
    @Test
    fun testValidLabelAccepted() {
        val controller = EnrollmentController()
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        controller.startTeaching()
        for (i in 1..3) {
            controller.recordSampleDirectly(createSequence(i * 0.1f))
        }

        val result = controller.saveGesture("HELP", store, matcher)
        assertTrue(result.isSuccess)
        assertEquals(EnrollmentState.IDLE, controller.state)
        assertEquals(1, store.profileCount)
    }

    /**
     * TEST 9: Three prototypes are stored under one gesture profile.
     */
    @Test
    fun testThreePrototypesUnderOneProfile() {
        val store = PersonalGestureStore()
        val seqs = listOf(createSequence(0.1f), createSequence(0.2f), createSequence(0.3f))

        val profile = store.createProfile("EMERGENCY", seqs)

        assertEquals("EMERGENCY", profile.label)
        assertEquals(3, profile.prototypes.size)
        assertEquals(3, store.totalPrototypeCount)
    }

    /**
     * TEST 10: Profile can be retrieved.
     */
    @Test
    fun testProfileRetrieval() {
        val store = PersonalGestureStore()
        val profile = store.createProfile("YES", listOf(createSequence(0.1f)))

        val retrieved = store.getProfile(profile.id)
        assertNotNull(retrieved)
        assertEquals("YES", retrieved?.label)
    }

    /**
     * TEST 11: Profile can be removed.
     */
    @Test
    fun testProfileRemoval() {
        val store = PersonalGestureStore()
        val profile = store.createProfile("NO", listOf(createSequence(0.1f)))
        assertEquals(1, store.profileCount)

        val removed = store.removeProfile(profile.id)
        assertTrue(removed)
        assertEquals(0, store.profileCount)
        assertNull(store.getProfile(profile.id))
    }

    /**
     * TEST 12: Invalid sequence is rejected.
     */
    @Test
    fun testInvalidSequenceRejected() {
        val controller = EnrollmentController()
        controller.startTeaching()
        controller.startRecordingCurrentSample()

        val emptySeq = TemporalSequence(emptyList(), windowSize = 30, isReady = false)
        val captured = controller.processFrame(hasHands = false, bufferSnapshot = emptySeq)

        assertFalse(captured)
        assertEquals(0, controller.capturedCount)
        assertEquals(EnrollmentState.RECORDING_1, controller.state)
    }

    /**
     * TEST 13: Recognition against multiple prototypes returns nearest prototype.
     */
    @Test
    fun testMultiplePrototypesRecognitionReturnsNearest() {
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        // Help gesture (offsets 1.0, 1.1, 1.2)
        val helpSeqs = listOf(createSequence(1.0f), createSequence(1.1f), createSequence(1.2f))
        store.createProfile("HELP", helpSeqs)

        // Thanks gesture (offsets 5.0, 5.1, 5.2)
        val thanksSeqs = listOf(createSequence(5.0f), createSequence(5.1f), createSequence(5.2f))
        store.createProfile("THANKS", thanksSeqs)

        for (proto in store.getAllPrototypes()) {
            matcher.addPrototype(proto)
        }

        // Live sequence close to Thanks Shot 2 (offset 5.12)
        val querySeq = createSequence(5.12f)
        val result = matcher.match(querySeq, threshold = 1.0)

        assertEquals(MatchStatus.MATCH, result.status)
        assertEquals("THANKS", result.recognizedLabel)
        assertTrue(result.nearestDistance < 1.0)
    }

    /**
     * TEST 14: Nearest prototype maps to correct gesture label.
     */
    @Test
    fun testNearestPrototypeMapsToCorrectLabel() {
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        store.createProfile("WATER", listOf(createSequence(2.0f), createSequence(2.1f), createSequence(2.2f)))
        for (proto in store.getAllPrototypes()) {
            matcher.addPrototype(proto)
        }

        val liveWater = createSequence(2.05f)
        val result = matcher.match(liveWater, threshold = 1.0)

        assertEquals("WATER", result.bestMatch?.displayName)
    }

    /**
     * TEST 15: Unknown distance returns UNKNOWN.
     */
    @Test
    fun testUnknownDistanceReturnsUnknown() {
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        store.createProfile("HELLO", listOf(createSequence(0.0f)))
        for (proto in store.getAllPrototypes()) {
            matcher.addPrototype(proto)
        }

        val uncalibratedGesture = createSequence(9.0f)
        val result = matcher.match(uncalibratedGesture, threshold = 0.5)

        assertEquals(MatchStatus.UNKNOWN, result.status)
        assertEquals("UNKNOWN", result.recognizedLabel)
    }

    /**
     * TEST 16: Duplicate gesture labels/IDs handled safely (replaces existing).
     */
    @Test
    fun testDuplicateGestureLabelsHandledSafely() {
        val store = PersonalGestureStore()
        store.createProfile("HELP", listOf(createSequence(1.0f)))
        assertEquals(1, store.profileCount)

        // Re-enrolling HELP replaces previous profile
        store.createProfile("HELP", listOf(createSequence(2.0f)))
        assertEquals(1, store.profileCount)
    }

    /**
     * TEST 17: Reset/cancel teaching clears partial enrollment state.
     */
    @Test
    fun testCancelClearsPartialState() {
        val controller = EnrollmentController()
        controller.startTeaching()
        controller.recordSampleDirectly(createSequence(0.1f))
        controller.recordSampleDirectly(createSequence(0.2f))
        assertEquals(2, controller.capturedCount)

        controller.cancel()

        assertEquals(EnrollmentState.IDLE, controller.state)
        assertEquals(0, controller.capturedCount)
        assertFalse(controller.isTeaching)
    }

    /**
     * TEST 18: Three prototype pairwise DTW distances can be calculated.
     */
    @Test
    fun testPairwiseIntraDistancesCalculated() {
        val seq1 = createSequence(0.1f)
        val seq2 = createSequence(0.12f)
        val seq3 = createSequence(0.15f)

        val profile = GestureProfile(
            id = "test_profile",
            label = "CONSISTENCY_TEST",
            prototypes = listOf(
                GesturePrototype("p1", "CONSISTENCY_TEST", seq1),
                GesturePrototype("p2", "CONSISTENCY_TEST", seq2),
                GesturePrototype("p3", "CONSISTENCY_TEST", seq3)
            )
        )

        val pairwise = profile.computePairwiseIntraDistances()
        assertEquals(3, pairwise.size) // P1 vs P2, P1 vs P3, P2 vs P3
        for (dist in pairwise) {
            assertTrue("Pairwise distance should be valid positive number", dist >= 0.0)
        }
        assertTrue(profile.meanIntraDistance() >= 0.0)
    }
}
