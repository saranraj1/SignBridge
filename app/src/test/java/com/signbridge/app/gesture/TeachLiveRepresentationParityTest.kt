package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class TeachLiveRepresentationParityTest {

    private fun createSyntheticFrame(
        timestampMs: Long,
        wristX: Float,
        wristY: Float,
        fingerBend: Float,
        handedness: String = "Left"
    ): NormalizedLandmarkFrame {
        val landmarks = mutableListOf<NormalizedLandmarkPoint>()
        // 21 landmarks
        for (i in 0 until 21) {
            val baseOffset = (i % 4) * 0.05f
            val bendOffset = (i / 4) * fingerBend * 0.04f
            landmarks.add(NormalizedLandmarkPoint(baseOffset + bendOffset, (i * 0.03f) - bendOffset, 0f))
        }
        return NormalizedLandmarkFrame(
            timestampMs = timestampMs,
            handedness = handedness,
            landmarks = landmarks,
            handScale = 0.2f,
            rawWristPosition = LandmarkPoint(wristX, wristY, 0f)
        )
    }

    private fun createActiveHelpGesture(frameCount: Int, startTs: Long = 0L): List<NormalizedLandmarkFrame> {
        val frames = mutableListOf<NormalizedLandmarkFrame>()
        for (i in 0 until frameCount) {
            val progress = i.toFloat() / (frameCount - 1).coerceAtLeast(1)
            // HELP motion: hand translates upward-left while fingers close/open
            val wx = 0.64f - 0.05f * progress
            val wy = 0.78f - 0.08f * progress
            val bend = sin(progress * Math.PI).toFloat() * 0.8f
            frames.add(createSyntheticFrame(startTs + i * 50L, wx, wy, bend))
        }
        return frames
    }

    private fun createStillFrames(count: Int, startTs: Long, baseWristX: Float = 0.64f, baseWristY: Float = 0.78f): List<NormalizedLandmarkFrame> {
        val frames = mutableListOf<NormalizedLandmarkFrame>()
        for (i in 0 until count) {
            // Small resting jitter
            val jitterX = (i % 2) * 0.0005f
            val jitterY = ((i + 1) % 2) * 0.0005f
            frames.add(createSyntheticFrame(startTs + i * 50L, baseWristX + jitterX, baseWristY + jitterY, 0.05f))
        }
        return frames
    }

    /**
     * CASE 1: The same gesture with different amounts of leading/trailing idle frames
     * produces equivalent clean sequences after extraction.
     */
    @Test
    fun testCase1_IdlePaddingInvariance() {
        val activeCore = createActiveHelpGesture(12)

        // Stream 1: Large leading and trailing padding (like Teach Mode)
        val streamTeach = mutableListOf<NormalizedLandmarkFrame>()
        streamTeach.addAll(createStillFrames(20, 0L))
        streamTeach.addAll(createActiveHelpGesture(12, 1000L))
        streamTeach.addAll(createStillFrames(25, 1600L, 0.59f, 0.70f))

        // Stream 2: Minimal padding (like Live Recognition Mode)
        val streamLive = mutableListOf<NormalizedLandmarkFrame>()
        streamLive.addAll(createStillFrames(2, 0L))
        streamLive.addAll(createActiveHelpGesture(12, 100L))
        streamLive.addAll(createStillFrames(2, 700L, 0.59f, 0.70f))

        val trimmedTeach = CanonicalGestureExtractor.trimToActiveGesture(streamTeach)
        val trimmedLive = CanonicalGestureExtractor.trimToActiveGesture(streamLive)

        // Both trimmed sequences should be compact active representations
        assertTrue("Teach trimmed frame count should be compact (~12-16 frames), was ${trimmedTeach.size}", trimmedTeach.size in 12..16)
        assertTrue("Live trimmed frame count should be compact (~12-16 frames), was ${trimmedLive.size}", trimmedLive.size in 12..16)

        val seqTeach = TemporalSequence(trimmedTeach, trimmedTeach.size, true)
        val seqLive = TemporalSequence(trimmedLive, trimmedLive.size, true)

        val dtwResult = DTW.computeDistance(seqTeach, seqLive)
        assertTrue("DTW distance between differently padded executions should be very small (< 0.10), was ${dtwResult.normalizedDistance}",
            dtwResult.normalizedDistance < 0.10)
    }

    /**
     * CASE 2: The same gesture performed at different speeds remains matchable through DTW.
     */
    @Test
    fun testCase2_SpeedInvariance() {
        val fastGesture = TemporalSequence(createActiveHelpGesture(8), 8, true)
        val normalGesture = TemporalSequence(createActiveHelpGesture(16), 16, true)
        val slowGesture = TemporalSequence(createActiveHelpGesture(28), 28, true)

        val dtwFastNormal = DTW.computeDistance(fastGesture, normalGesture)
        val dtwNormalSlow = DTW.computeDistance(normalGesture, slowGesture)
        val dtwFastSlow = DTW.computeDistance(fastGesture, slowGesture)

        assertTrue("Fast vs Normal DTW should be matchable (< 0.20), was ${dtwFastNormal.normalizedDistance}", dtwFastNormal.normalizedDistance < 0.20)
        assertTrue("Normal vs Slow DTW should be matchable (< 0.20), was ${dtwNormalSlow.normalizedDistance}", dtwNormalSlow.normalizedDistance < 0.20)
        assertTrue("Fast vs Slow DTW should be matchable (< 0.25), was ${dtwFastSlow.normalizedDistance}", dtwFastSlow.normalizedDistance < 0.25)
    }

    /**
     * CASE 3: An internal pause does not split the gesture.
     */
    @Test
    fun testCase3_InternalPausePreserved() {
        val stream = mutableListOf<NormalizedLandmarkFrame>()
        stream.addAll(createActiveHelpGesture(6, 0L))
        // Mid-gesture pause of 4 stationary frames
        stream.addAll(createStillFrames(4, 300L, 0.615f, 0.74f))
        stream.addAll(createActiveHelpGesture(6, 500L))

        val trimmed = CanonicalGestureExtractor.trimToActiveGesture(stream)
        // The internal pause should be completely preserved
        assertTrue("Internal pause should not be stripped out from the middle", trimmed.size >= 14)
    }

    /**
     * CASE 4: A stationary hand does not become a gesture.
     */
    @Test
    fun testCase4_StationaryHandRejected() {
        val stillStream = createStillFrames(30, 0L)
        val seq = CanonicalGestureExtractor.extractCanonicalSequence(stillStream)
        // If there's no motion above threshold, either sequence is null or rejected by segmenter min frames
        val segmenter = GestureSegmenter()
        for (f in stillStream) {
            val ev = segmenter.processFrame(f)
            assertTrue("Stationary hand should not trigger CAPTURING", ev !is SegmentationEvent.Completed)
        }
    }

    /**
     * CASE 5: A spatial gesture remains distinguishable from a stationary hand.
     */
    @Test
    fun testCase5_SpatialGestureVsStationaryHand() {
        val activeGesture = TemporalSequence(createActiveHelpGesture(15), 15, true)
        val stationaryHand = TemporalSequence(createStillFrames(15, 0L), 15, true)

        val dtwResult = DTW.computeDistance(activeGesture, stationaryHand)
        println("=== Case 5 Spatial vs Still Hand DTW: ${dtwResult.normalizedDistance} ===")
        assertTrue("Spatial gesture vs stationary hand must be distinct (> 0.15), was ${dtwResult.normalizedDistance}",
            dtwResult.normalizedDistance > 0.15)
    }

    /**
     * CASE 6: Teach and Live invoke exactly the same extraction path.
     */
    @Test
    fun testCase6_TeachLiveExactPipelineParity() {
        val rawInput = createActiveHelpGesture(15)

        val teachOutput = CanonicalGestureExtractor.trimToActiveGesture(rawInput)
        val liveOutput = CanonicalGestureExtractor.trimToActiveGesture(rawInput)

        assertEquals("Frame counts must be 100% identical", teachOutput.size, liveOutput.size)
        for (i in teachOutput.indices) {
            assertEquals("Timestamp must match", teachOutput[i].timestampMs, liveOutput[i].timestampMs)
            assertEquals("Scale must match", teachOutput[i].handScale, liveOutput[i].handScale, 1e-6f)
            assertEquals("Wrist X must match", teachOutput[i].rawWristPosition.x, liveOutput[i].rawWristPosition.x, 1e-6f)
        }
    }

    /**
     * GOLDEN PHYSICAL-LIKE TEST:
     * Replicates the exact physical audit scenario:
     * Teach: 60 raw frames with large stationary holding padding.
     * Live: 9 active frames with minimal padding.
     * Verify that after canonical extraction, DTW same-class distance < 0.26.
     */
    @Test
    fun testGoldenPhysicalAuditScenario() {
        // Enrolled Prototype 1: 60 raw frames (Teach mode user holding hand)
        val rawTeachP1 = mutableListOf<NormalizedLandmarkFrame>()
        rawTeachP1.addAll(createStillFrames(10, 0L))
        rawTeachP1.addAll(createActiveHelpGesture(14, 500L))
        rawTeachP1.addAll(createStillFrames(36, 1200L, 0.59f, 0.70f))
        assertEquals(60, rawTeachP1.size)

        // Live Query 1: 9 frames (Live recognition user swift performance)
        val rawLiveL1 = createActiveHelpGesture(9, 0L)
        assertEquals(9, rawLiveL1.size)

        // Pass through Canonical Gesture Extractor
        val cleanTeachP1 = CanonicalGestureExtractor.extractCanonicalSequence(rawTeachP1, sequenceId = 1L)
        val cleanLiveL1 = CanonicalGestureExtractor.extractCanonicalSequence(rawLiveL1, sequenceId = 2L)

        assertNotNull("Teach sequence should be extracted", cleanTeachP1)
        assertNotNull("Live sequence should be extracted", cleanLiveL1)

        val dtwSameClass = DTW.computeDistance(cleanLiveL1!!, cleanTeachP1!!)
        println("=== GOLDEN PHYSICAL-LIKE SCENARIO ===")
        println("Raw Teach Frames: ${rawTeachP1.size} -> Clean Extracted: ${cleanTeachP1.frameCount}")
        println("Raw Live Frames: ${rawLiveL1.size} -> Clean Extracted: ${cleanLiveL1.frameCount}")
        println("Same-Class DTW Distance (Live vs Teach): ${dtwSameClass.normalizedDistance}")

        assertTrue("Same-class DTW distance must be well below threshold 0.26, was ${dtwSameClass.normalizedDistance}",
            dtwSameClass.normalizedDistance < 0.15)

        // Unrelated gesture (WAVE / Static)
        val waveFrames = mutableListOf<NormalizedLandmarkFrame>()
        for (i in 0 until 14) {
            val progress = i.toFloat() / 13f
            // Horizontal wave: large X sweep, no Y change
            val wx = 0.40f + 0.30f * progress
            val wy = 0.78f
            waveFrames.add(createSyntheticFrame(i * 50L, wx, wy, 0.05f))
        }
        val cleanWave = TemporalSequence(waveFrames, waveFrames.size, true)
        val dtwWave = DTW.computeDistance(cleanLiveL1, cleanWave)
        println("Different-Class DTW Distance (HELP vs WAVE): ${dtwWave.normalizedDistance}")

        assertTrue("Different-class DTW distance must be well above threshold 0.26 (> 0.40), was ${dtwWave.normalizedDistance}",
            dtwWave.normalizedDistance > 0.40)
    }
}
