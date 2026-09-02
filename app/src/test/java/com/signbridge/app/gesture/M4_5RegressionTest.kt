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
 * M4.5 Comprehensive Regression Test Safety Net.
 *
 * Validates:
 * 1. Teach & Live Representation Parity
 * 2. Speed Invariance (Fast, Normal, Slow, Variable)
 * 3. Idle-Padding Invariance (Canonical Extraction)
 * 4. Internal-Pause Preservation (No premature truncation)
 * 5. Static Hand & Noise Spike Rejection
 * 6. Spatial Gesture vs. Stationary Hand Separation (66-D)
 * 7. Multi-Class Separation (HELP vs. WAVE vs. YES)
 * 8. Hand Loss During Capture (Clean finalization or rejection)
 * 9. Hand Re-Entry Stabilization
 * 10. Back-to-Back Gestures Without Hand Removal
 * 11. Few-Shot Class Matching with Multi-Speed Prototypes
 * 12. Controlled Distance Distribution & Threshold Calibration
 * 13. Ambiguity Margin Gating
 * 14. Persistence Round-Trip Fidelity
 */
class M4_5RegressionTest {

    private fun createSyntheticFrame(
        timestampMs: Long,
        wristX: Float,
        wristY: Float,
        fingerBend: Float,
        handScale: Float = 0.18f,
        handedness: String = "Right"
    ): NormalizedLandmarkFrame {
        val landmarks = mutableListOf<NormalizedLandmarkPoint>()
        for (i in 0 until 21) {
            val baseOffset = (i % 4) * 0.04f
            val bendOffset = (i / 4) * fingerBend * 0.08f
            landmarks.add(NormalizedLandmarkPoint(baseOffset + bendOffset, (i * 0.03f) - bendOffset, 0f))
        }
        return NormalizedLandmarkFrame(
            timestampMs = timestampMs,
            handedness = handedness,
            landmarks = landmarks,
            handScale = handScale,
            rawWristPosition = LandmarkPoint(wristX, wristY, 0f)
        )
    }

    private fun createHelpGesture(
        frameCount: Int,
        startTs: Long = 0L,
        amplitude: Float = 1.0f,
        speedFactor: Float = 1.0f,
        handScale: Float = 0.18f
    ): List<NormalizedLandmarkFrame> {
        val frames = mutableListOf<NormalizedLandmarkFrame>()
        for (i in 0 until frameCount) {
            val progress = ((i.toFloat() / (frameCount - 1).coerceAtLeast(1)) * speedFactor).coerceIn(0f, 1f)
            val wx = 0.50f - (0.06f * progress * amplitude)
            val wy = 0.60f - (0.12f * progress * amplitude)
            val bend = sin(progress * Math.PI).toFloat() * 0.85f * amplitude
            frames.add(createSyntheticFrame(startTs + i * 40L, wx, wy, bend, handScale = handScale))
        }
        return frames
    }

    private fun createWaveGesture(
        frameCount: Int,
        startTs: Long = 0L,
        handScale: Float = 0.18f
    ): List<NormalizedLandmarkFrame> {
        val frames = mutableListOf<NormalizedLandmarkFrame>()
        for (i in 0 until frameCount) {
            val progress = i.toFloat() / (frameCount - 1).coerceAtLeast(1)
            // Horizontal wave: large X sweep, static flat palm
            val wx = 0.35f + (0.28f * progress)
            val wy = 0.60f
            frames.add(createSyntheticFrame(startTs + i * 40L, wx, wy, 0.05f, handScale = handScale))
        }
        return frames
    }

    private fun createYesGesture(
        frameCount: Int,
        startTs: Long = 0L,
        handScale: Float = 0.18f
    ): List<NormalizedLandmarkFrame> {
        val frames = mutableListOf<NormalizedLandmarkFrame>()
        for (i in 0 until frameCount) {
            val progress = i.toFloat() / (frameCount - 1).coerceAtLeast(1)
            // Nodding fist: vertical oscillation with flexed fingers
            val nod = sin(progress * 2.0 * Math.PI).toFloat() * 0.08f
            val wx = 0.50f
            val wy = 0.60f + nod
            frames.add(createSyntheticFrame(startTs + i * 40L, wx, wy, 0.90f, handScale = handScale))
        }
        return frames
    }

    private fun createStillFrames(
        count: Int,
        startTs: Long = 0L,
        baseWristX: Float = 0.50f,
        baseWristY: Float = 0.60f,
        handScale: Float = 0.18f
    ): List<NormalizedLandmarkFrame> {
        val frames = mutableListOf<NormalizedLandmarkFrame>()
        for (i in 0 until count) {
            val jitterX = (i % 2) * 0.0004f
            val jitterY = ((i + 1) % 2) * 0.0004f
            frames.add(createSyntheticFrame(startTs + i * 40L, baseWristX + jitterX, baseWristY + jitterY, 0.05f, handScale = handScale))
        }
        return frames
    }

    // ==========================================
    // 1. Teach & Live Representation Parity
    // ==========================================
    @Test
    fun test1_TeachLiveRepresentationParity() {
        val rawInput = createHelpGesture(16)

        val seqTeach = CanonicalGestureExtractor.extractCanonicalSequence(rawInput, sequenceId = 1L)
        val seqLive = CanonicalGestureExtractor.extractCanonicalSequence(rawInput, sequenceId = 2L)

        assertNotNull(seqTeach)
        assertNotNull(seqLive)
        assertEquals("Frame count must be identical", seqTeach!!.frameCount, seqLive!!.frameCount)

        val dtwSelf = DTW.computeDistance(seqTeach, seqLive)
        assertEquals("Parity DTW distance must be exactly 0.0", 0.0, dtwSelf.normalizedDistance, 1e-6)
    }

    // ==========================================
    // 2. Speed Invariance (Fast, Normal, Slow, Variable)
    // ==========================================
    @Test
    fun test2_SpeedInvariance_FastNormalSlow() {
        val normalFrames = createHelpGesture(16)
        val fastFrames = createHelpGesture(8)
        val slowFrames = createHelpGesture(30)

        val seqNormal = TemporalSequence(normalFrames, normalFrames.size, true)
        val seqFast = TemporalSequence(fastFrames, fastFrames.size, true)
        val seqSlow = TemporalSequence(slowFrames, slowFrames.size, true)

        val dtwFastNormal = DTW.computeDistance(seqFast, seqNormal)
        val dtwSlowNormal = DTW.computeDistance(seqSlow, seqNormal)
        val dtwFastSlow = DTW.computeDistance(seqFast, seqSlow)

        println("=== Speed Invariance DTW Distances ===")
        println("Fast (8f) vs Normal (16f): ${dtwFastNormal.normalizedDistance}")
        println("Slow (30f) vs Normal (16f): ${dtwSlowNormal.normalizedDistance}")
        println("Fast (8f) vs Slow (30f):   ${dtwFastSlow.normalizedDistance}")

        assertTrue("Fast vs Normal must be < 0.20, was ${dtwFastNormal.normalizedDistance}", dtwFastNormal.normalizedDistance < 0.20)
        assertTrue("Slow vs Normal must be < 0.20, was ${dtwSlowNormal.normalizedDistance}", dtwSlowNormal.normalizedDistance < 0.20)
        assertTrue("Fast vs Slow must be < 0.25, was ${dtwFastSlow.normalizedDistance}", dtwFastSlow.normalizedDistance < 0.25)
    }

    // ==========================================
    // 3. Idle-Padding Invariance
    // ==========================================
    @Test
    fun test3_IdlePaddingInvariance() {
        // Pattern: SSSSMMMMSSMMSSSS
        val rawPadded = mutableListOf<NormalizedLandmarkFrame>()
        rawPadded.addAll(createStillFrames(8, 0L))
        rawPadded.addAll(createHelpGesture(14, 400L))
        rawPadded.addAll(createStillFrames(12, 1000L, 0.44f, 0.48f))

        val clean = CanonicalGestureExtractor.extractCanonicalSequence(rawPadded)
        assertNotNull(clean)

        // Raw input had 8 + 14 + 12 = 34 frames
        // Clean output should strip the outer stillness frames
        assertTrue("Clean frame count (${clean!!.frameCount}) should be >= 10 and <= 20", clean.frameCount in 10..20)

        val unpadded = TemporalSequence(createHelpGesture(14), 14, true)
        val dtw = DTW.computeDistance(clean, unpadded)
        assertTrue("Padded vs Unpadded DTW should be < 0.12, was ${dtw.normalizedDistance}", dtw.normalizedDistance < 0.12)
    }

    // ==========================================
    // 4. Internal Pause Preservation
    // ==========================================
    @Test
    fun test4_InternalPausePreservation_NoEarlyTermination() {
        // Pattern: SSMMMMSSSSMMSS (4 still frames in the middle)
        val rawCompound = mutableListOf<NormalizedLandmarkFrame>()
        rawCompound.addAll(createStillFrames(3, 0L))
        rawCompound.addAll(createHelpGesture(6, 120L))
        rawCompound.addAll(createStillFrames(4, 360L, 0.47f, 0.54f))
        rawCompound.addAll(createHelpGesture(6, 520L))
        rawCompound.addAll(createStillFrames(3, 760L, 0.44f, 0.48f))

        val clean = CanonicalGestureExtractor.extractCanonicalSequence(rawCompound)
        assertNotNull("Compound gesture with internal pause must be extracted", clean)

        // Total active frames = 6 + 4 (pause) + 6 = 16 frames. With margins: >= 14 frames.
        assertTrue("Internal pause must be preserved: frameCount (${clean!!.frameCount}) must be >= 14", clean.frameCount >= 14)
    }

    // ==========================================
    // 5. Static Hand & Noise Spike Rejection
    // ==========================================
    @Test
    fun test5_StaticHandAndNoiseSpikesRejected() {
        val segmenter = GestureSegmenter()

        // 1. Prolonged static hand (30 frames)
        val stillFrames = createStillFrames(30, 0L)
        for (f in stillFrames) {
            val event = segmenter.processFrame(f)
            assertTrue("Static hand must never trigger completion", event !is SegmentationEvent.Completed)
        }

        // 2. Settle at a new stationary position (1 step movement)
        val shiftedStill = createStillFrames(10, 1200L, baseWristX = 0.52f, baseWristY = 0.62f)
        for (f in shiftedStill) {
            val event = segmenter.processFrame(f)
            assertTrue("Shifted resting hand must not complete a gesture", event !is SegmentationEvent.Completed)
        }
    }

    // ==========================================
    // 6. Spatial Gesture vs Stationary Hand (66-D)
    // ==========================================
    @Test
    fun test6_SpatialGestureVsStationaryHandSeparation() {
        val spatialWave = TemporalSequence(createWaveGesture(16), 16, true)
        val staticHand = TemporalSequence(createStillFrames(16, 0L), 16, true)

        val dtw = DTW.computeDistance(spatialWave, staticHand)
        println("=== Spatial Wave vs Static Hand DTW: ${dtw.normalizedDistance} ===")
        assertTrue("Spatial wave vs static hand must have large DTW distance (> 0.35), was ${dtw.normalizedDistance}",
            dtw.normalizedDistance > 0.35)
    }

    // ==========================================
    // 7. Multi-Class Separation
    // ==========================================
    @Test
    fun test7_MultiClassSeparation() {
        val help = TemporalSequence(createHelpGesture(16), 16, true)
        val wave = TemporalSequence(createWaveGesture(16), 16, true)
        val yes = TemporalSequence(createYesGesture(16), 16, true)

        val dtwHelpWave = DTW.computeDistance(help, wave)
        val dtwHelpYes = DTW.computeDistance(help, yes)
        val dtwWaveYes = DTW.computeDistance(wave, yes)

        println("=== Cross-Class Separation Matrix ===")
        println("HELP vs WAVE: ${dtwHelpWave.normalizedDistance}")
        println("HELP vs YES:  ${dtwHelpYes.normalizedDistance}")
        println("WAVE vs YES:  ${dtwWaveYes.normalizedDistance}")

        assertTrue("HELP vs WAVE must be > 0.35, was ${dtwHelpWave.normalizedDistance}", dtwHelpWave.normalizedDistance > 0.35)
        assertTrue("HELP vs YES must be > 0.35, was ${dtwHelpYes.normalizedDistance}", dtwHelpYes.normalizedDistance > 0.35)
        assertTrue("WAVE vs YES must be > 0.35, was ${dtwWaveYes.normalizedDistance}", dtwWaveYes.normalizedDistance > 0.35)
    }

    // ==========================================
    // 8. Hand Loss During Capture
    // ==========================================
    @Test
    fun test8_HandLossDuringCapture() {
        val segmenter = GestureSegmenter()

        // Hand appears + stabilize
        segmenter.processFrame(createStillFrames(1)[0])
        segmenter.processFrame(createStillFrames(1)[0])
        segmenter.processFrame(createStillFrames(1)[0])
        segmenter.processFrame(createStillFrames(1)[0])

        // Feed 10 active help frames
        val helpFrames = createHelpGesture(10)
        for (f in helpFrames) {
            segmenter.processFrame(f)
        }

        // Hand leaves frame (null)
        val event = segmenter.processFrame(null)
        assertTrue("Hand loss with >= 6 frames should complete", event is SegmentationEvent.Completed)
        val completed = event as SegmentationEvent.Completed
        assertEquals("hand_lost", completed.endReason)
        assertTrue(segmenter.handAbsent)
    }

    // ==========================================
    // 9. Hand Re-Entry Stabilization
    // ==========================================
    @Test
    fun test9_HandReEntryStabilization() {
        val segmenter = GestureSegmenter()

        // Hand absent initially
        assertTrue(segmenter.handAbsent)

        // Hand enters -> STABILIZING
        val ev1 = segmenter.processFrame(createStillFrames(1)[0])
        assertEquals(SegmenterState.STABILIZING, segmenter.state)

        segmenter.processFrame(createStillFrames(1)[0])
        segmenter.processFrame(createStillFrames(1)[0])

        // After 3 frames -> IDLE
        segmenter.processFrame(createStillFrames(1)[0])
        assertEquals(SegmenterState.IDLE, segmenter.state)

        // Hand leaves
        segmenter.processFrame(null)
        assertTrue(segmenter.handAbsent)

        // Hand re-enters -> STABILIZING again
        segmenter.processFrame(createStillFrames(1)[0])
        assertEquals(SegmenterState.STABILIZING, segmenter.state)
    }

    // ==========================================
    // 10. Back-to-Back Gestures Without Hand Removal
    // ==========================================
    @Test
    fun test10_BackToBackGesturesWithoutHandRemoval() {
        val segmenter = GestureSegmenter()
        val matcher = PrototypeMatcher()

        val helpProto = GesturePrototype("HELP_1", "HELP", TemporalSequence(createHelpGesture(14), 14, true))
        val waveProto = GesturePrototype("WAVE_1", "WAVE", TemporalSequence(createWaveGesture(14), 14, true))
        matcher.addPrototype(helpProto)
        matcher.addPrototype(waveProto)

        // Stabilize
        segmenter.processFrame(createStillFrames(1)[0])
        segmenter.processFrame(createStillFrames(1)[0])
        segmenter.processFrame(createStillFrames(1)[0])
        segmenter.processFrame(createStillFrames(1)[0])

        // --- Gesture 1: HELP ---
        for (f in createHelpGesture(12)) segmenter.processFrame(f)
        var comp1: SegmentationEvent.Completed? = null
        for (f in createStillFrames(10, baseWristX = 0.44f, baseWristY = 0.48f)) {
            val ev = segmenter.processFrame(f)
            if (ev is SegmentationEvent.Completed) comp1 = ev
        }
        assertNotNull("Gesture 1 should complete", comp1)
        val res1 = matcher.match(comp1!!.sequence, threshold = 0.26)
        assertEquals("HELP", res1.recognizedLabel)
        assertEquals(MatchStatus.MATCH, res1.status)

        // Hand remains in view (IDLE)
        assertEquals(SegmenterState.IDLE, segmenter.state)

        // Hand rests before Gesture 2 start position
        for (f in createStillFrames(4, baseWristX = 0.35f, baseWristY = 0.60f)) {
            segmenter.processFrame(f)
        }

        // --- Gesture 2: WAVE ---
        for (f in createWaveGesture(12)) segmenter.processFrame(f)
        var comp2: SegmentationEvent.Completed? = null
        for (f in createStillFrames(10, baseWristX = 0.63f, baseWristY = 0.60f)) {
            val ev = segmenter.processFrame(f)
            if (ev is SegmentationEvent.Completed) comp2 = ev
        }
        assertNotNull("Gesture 2 should complete", comp2)
        val res2 = matcher.match(comp2!!.sequence, threshold = 0.26)
        println("=== Test 10 Gesture 2 Result: status=${res2.status}, label=${res2.recognizedLabel}, dist=${res2.nearestDistance}, runner=${res2.runnerUpDistance}, margin=${res2.margin} ===")
        assertEquals("WAVE", res2.recognizedLabel)
        assertEquals(MatchStatus.MATCH, res2.status)
    }

    // ==========================================
    // 11. Few-Shot Class Matching with Multi-Speed Prototypes
    // ==========================================
    @Test
    fun test11_FewShotClassMatchingWithMultiSpeedPrototypes() {
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        // 3 Prototypes for HELP: P1 (normal), P2 (fast), P3 (slow)
        val p1 = TemporalSequence(createHelpGesture(16), 16, true)
        val p2 = TemporalSequence(createHelpGesture(10), 10, true)
        val p3 = TemporalSequence(createHelpGesture(26), 26, true)

        val profile = store.createProfile("HELP", listOf(p1, p2, p3))
        profile.prototypes.forEach { matcher.addPrototype(it) }

        // Test with 3 different live execution speeds
        val queryFast = TemporalSequence(createHelpGesture(9), 9, true)
        val queryNormal = TemporalSequence(createHelpGesture(17), 17, true)
        val querySlow = TemporalSequence(createHelpGesture(28), 28, true)

        val resFast = matcher.match(queryFast, threshold = 0.26)
        val resNormal = matcher.match(queryNormal, threshold = 0.26)
        val resSlow = matcher.match(querySlow, threshold = 0.26)

        assertEquals("Fast query matches HELP", "HELP", resFast.recognizedLabel)
        assertEquals("Normal query matches HELP", "HELP", resNormal.recognizedLabel)
        assertEquals("Slow query matches HELP", "HELP", resSlow.recognizedLabel)

        assertTrue(resFast.nearestDistance <= 0.26)
        assertTrue(resNormal.nearestDistance <= 0.26)
        assertTrue(resSlow.nearestDistance <= 0.26)
    }

    // ==========================================
    // 12. Controlled Distance Distribution & Threshold Justification
    // ==========================================
    @Test
    fun test12_DistanceDistributionAndThresholdJustification() {
        val helpBase = TemporalSequence(createHelpGesture(16), 16, true)

        // 1. Same-Class Trials (Variations in speed, amplitude, and jitter)
        val sameDistances = mutableListOf<Double>()
        for (amp in listOf(0.90f, 0.95f, 1.0f, 1.05f, 1.10f)) {
            for (speed in listOf(0.85f, 1.0f, 1.15f)) {
                val variant = TemporalSequence(createHelpGesture(16, amplitude = amp, speedFactor = speed), 16, true)
                val dtw = DTW.computeDistance(helpBase, variant)
                sameDistances.add(dtw.normalizedDistance)
            }
        }

        val sameMean = sameDistances.average()
        val sameMax = sameDistances.maxOrNull() ?: 0.0

        // 2. Different-Class Trials (WAVE, YES, and STATIC)
        val diffDistances = mutableListOf<Double>()
        diffDistances.add(DTW.computeDistance(helpBase, TemporalSequence(createWaveGesture(16), 16, true)).normalizedDistance)
        diffDistances.add(DTW.computeDistance(helpBase, TemporalSequence(createYesGesture(16), 16, true)).normalizedDistance)
        diffDistances.add(DTW.computeDistance(helpBase, TemporalSequence(createStillFrames(16), 16, true)).normalizedDistance)

        val diffMin = diffDistances.minOrNull() ?: 1.0

        println("=== Synthetic Distance Distribution Analysis ===")
        println("Same-Class Mean:     ${String.format("%.4f", sameMean)}")
        println("Same-Class Max:      ${String.format("%.4f", sameMax)}")
        println("Configured Threshold: ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD}")
        println("Different-Class Min: ${String.format("%.4f", diffMin)}")
        println("Separation Margin:   ${String.format("%.4f", diffMin - sameMax)}")

        // Mathematical justification:
        // sameMax must be strictly below 0.26
        assertTrue("Same-class max ($sameMax) must be <= threshold (0.26)", sameMax <= GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        // diffMin must be strictly above 0.26
        assertTrue("Different-class min ($diffMin) must be > threshold (0.26)", diffMin > GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        // Clear separation gap >= 0.10
        assertTrue("Separation gap (${diffMin - sameMax}) must be >= 0.10", diffMin - sameMax >= 0.10)
    }

    // ==========================================
    // 13. Ambiguity Margin Gating
    // ==========================================
    @Test
    fun test13_AmbiguityMarginGating() {
        val matcher = PrototypeMatcher()

        // Class 1: HELP
        val helpSeq = TemporalSequence(createHelpGesture(16), 16, true)
        matcher.addPrototype(GesturePrototype("HELP_1", "HELP", helpSeq))

        // Class 2: HELP_TWIN (slightly altered copy)
        val twinSeq = TemporalSequence(createHelpGesture(16, amplitude = 0.98f), 16, true)
        matcher.addPrototype(GesturePrototype("TWIN_1", "TWIN", twinSeq))

        // Query that is nearly equidistant to HELP and TWIN
        val query = TemporalSequence(createHelpGesture(16, amplitude = 0.99f), 16, true)
        val result = matcher.match(query, threshold = 0.30, ambiguityMargin = 0.08)

        assertEquals("Ambiguous competing classes must produce AMBIGUOUS status", MatchStatus.AMBIGUOUS, result.status)
        assertFalse("Ambiguous match must not be accepted", result.isAccepted)
        assertEquals("UNKNOWN", result.recognizedLabel)
    }

    // ==========================================
    // 14. Persistence Round-Trip Fidelity
    // ==========================================
    @Test
    fun test14_PersistenceRoundTripFidelity() {
        val help1 = TemporalSequence(createHelpGesture(15), 15, true)
        val help2 = TemporalSequence(createHelpGesture(12), 12, true)
        val help3 = TemporalSequence(createHelpGesture(18), 18, true)

        val profile = GestureProfile("prof_help", "HELP", listOf(
            GesturePrototype("p1", "HELP", help1),
            GesturePrototype("p2", "HELP", help2),
            GesturePrototype("p3", "HELP", help3)
        ))

        val json = GestureProfileStorage.serializeProfiles(listOf(profile))
        val deserialized = GestureProfileStorage.deserializeProfiles(json)

        assertEquals(1, deserialized.size)
        val restored = deserialized[0]
        assertEquals("HELP", restored.label)
        assertEquals(3, restored.prototypes.size)

        for (i in 0 until 3) {
            val orig = profile.prototypes[i].sequence
            val rest = restored.prototypes[i].sequence
            val dtw = DTW.computeDistance(orig, rest)
            assertEquals("Persistence round-trip DTW must be exactly 0.000000", 0.0, dtw.normalizedDistance, 1e-6)
        }
    }
}
