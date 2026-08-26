package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class DTWTest {

    private fun createFrame(scale: Float, offset: Float, timestampMs: Long): NormalizedLandmarkFrame {
        val points = (0 until 21).map { i ->
            NormalizedLandmarkPoint(
                x = (i * 0.05f + offset) * scale,
                y = (i * 0.03f + offset) * scale,
                z = (i * 0.01f) * scale
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

    private fun createSyntheticSequence(
        length: Int,
        offset: Float = 0.0f,
        scale: Float = 1.0f,
        stepMs: Long = 33L
    ): TemporalSequence {
        val frames = (0 until length).map { i ->
            createFrame(scale = scale, offset = offset + (i * 0.01f), timestampMs = i * stepMs)
        }
        return TemporalSequence(frames = frames, windowSize = length, isReady = true)
    }

    /**
     * TEST 1: Identical sequences.
     * Distance between a sequence and itself must be 0.0 (or within floating-point tolerance).
     */
    @Test
    fun testIdenticalSequencesZeroDistance() {
        val seq = createSyntheticSequence(length = 30, offset = 0.1f)
        val result = DTW.computeDistance(seq, seq)

        assertTrue("Result must be valid", result.isValid)
        assertEquals(0.0, result.accumulatedCost, 1e-6)
        assertEquals(0.0, result.normalizedDistance, 1e-6)
        assertEquals(30, result.sequenceALength)
        assertEquals(30, result.sequenceBLength)
    }

    /**
     * TEST 2: Two identical sequences with temporal repetition / speed variation.
     * DTW should align warped frames and keep distance very low.
     * A = [f1, f2, f3, f4]
     * B = [f1, f1, f2, f3, f4, f4]
     */
    @Test
    fun testWarpedTemporalSpeedTolerance() {
        val f1 = createFrame(scale = 1.0f, offset = 0.1f, timestampMs = 100L)
        val f2 = createFrame(scale = 1.0f, offset = 0.2f, timestampMs = 200L)
        val f3 = createFrame(scale = 1.0f, offset = 0.3f, timestampMs = 300L)
        val f4 = createFrame(scale = 1.0f, offset = 0.4f, timestampMs = 400L)

        val seqA = TemporalSequence(listOf(f1, f2, f3, f4), windowSize = 4, isReady = true)
        // Stretched sequence with frame repetitions (simulating slower gesture)
        val seqB = TemporalSequence(listOf(f1, f1, f2, f3, f4, f4), windowSize = 6, isReady = true)

        val result = DTW.computeDistance(seqA, seqB)

        assertTrue(result.isValid)
        // Because repeated frames match the corresponding original frames with 0 cost, total cost is 0.0
        assertEquals(0.0, result.accumulatedCost, 1e-5)
        assertEquals(0.0, result.normalizedDistance, 1e-5)
    }

    /**
     * TEST 3: Clearly different sequences.
     * Distance between distinct gestures must be significantly higher than intra-gesture distance.
     */
    @Test
    fun testDistinctSequencesProduceLargeDistance() {
        val seqA = createSyntheticSequence(length = 30, offset = 0.0f)
        val seqB = createSyntheticSequence(length = 30, offset = 2.0f) // Substantially different coordinates

        val result = DTW.computeDistance(seqA, seqB)

        assertTrue(result.isValid)
        assertTrue("Distinct sequence distance must be significant (> 1.0)", result.normalizedDistance > 1.0)
    }

    /**
     * TEST 4: Empty sequence safe handling.
     */
    @Test
    fun testEmptySequenceHandling() {
        val emptySeq = TemporalSequence(emptyList(), windowSize = 30, isReady = false)
        val normalSeq = createSyntheticSequence(length = 30)

        // Both empty
        val bothEmptyResult = DTW.computeDistance(emptySeq, emptySeq)
        assertEquals(0.0, bothEmptyResult.accumulatedCost, 1e-6)
        assertEquals(0.0, bothEmptyResult.normalizedDistance, 1e-6)

        // One empty, one non-empty -> INVALID
        val oneEmptyResult = DTW.computeDistance(emptySeq, normalSeq)
        assertFalse(oneEmptyResult.isValid)
        assertEquals(Double.POSITIVE_INFINITY, oneEmptyResult.normalizedDistance, 0.0)
    }

    /**
     * TEST 5: Single-frame sequences.
     */
    @Test
    fun testSingleFrameSequences() {
        val f1 = createFrame(scale = 1.0f, offset = 0.1f, timestampMs = 100L)
        val f2 = createFrame(scale = 1.0f, offset = 0.2f, timestampMs = 100L)

        val seqA = TemporalSequence(listOf(f1), windowSize = 1, isReady = true)
        val seqB = TemporalSequence(listOf(f2), windowSize = 1, isReady = true)

        val result = DTW.computeDistance(seqA, seqB)
        assertTrue(result.isValid)

        val expectedFrameDist = DTW.frameDistance(f1.toFeatureVector(), f2.toFeatureVector())
        assertEquals(expectedFrameDist, result.accumulatedCost, 1e-5)
        assertEquals(expectedFrameDist / 2.0, result.normalizedDistance, 1e-5)
    }

    /**
     * TEST 6: Mismatched feature dimensions rejection.
     */
    @Test
    fun testMismatchedFeatureDimensions() {
        val vec63 = FloatArray(63) { 1.0f }
        val vec10 = FloatArray(10) { 1.0f }

        val dist = DTW.frameDistance(vec63, vec10)
        assertEquals(Double.POSITIVE_INFINITY, dist, 0.0)

        val result = DTW.computeDistance(listOf(vec63), listOf(vec10))
        assertFalse(result.isValid)
    }

    /**
     * TEST 7: NaN / Infinite values safe handling.
     */
    @Test
    fun testNaNAndInfiniteHandling() {
        val vecValid = FloatArray(63) { 0.5f }
        val vecNaN = FloatArray(63) { 0.5f }.apply { this[10] = Float.NaN }
        val vecInf = FloatArray(63) { 0.5f }.apply { this[20] = Float.POSITIVE_INFINITY }

        assertEquals(Double.POSITIVE_INFINITY, DTW.frameDistance(vecValid, vecNaN), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, DTW.frameDistance(vecValid, vecInf), 0.0)

        val resultNaN = DTW.computeDistance(listOf(vecValid), listOf(vecNaN))
        assertFalse(resultNaN.isValid)
    }

    /**
     * TEST 8: Path / accumulated-cost matrix correctness on a small 1D vector example.
     * Let seqA = [[1]], [[2]], [[3]]
     * Let seqB = [[1]], [[2]], [[2]], [[3]]
     * Costs:
     * d(1,1)=0, d(1,2)=1, d(1,2)=1, d(1,3)=2
     * d(2,1)=1, d(2,2)=0, d(2,2)=0, d(2,3)=1
     * d(3,1)=2, d(3,2)=1, d(3,2)=1, d(3,3)=0
     * Optimal warping path matches (1->1), (2->2), (2->2), (3->3) => total cost = 0.
     */
    @Test
    fun testManualMatrixCostVerification() {
        val seqA = listOf(floatArrayOf(1.0f), floatArrayOf(2.0f), floatArrayOf(3.0f))
        val seqB = listOf(floatArrayOf(1.0f), floatArrayOf(2.0f), floatArrayOf(2.0f), floatArrayOf(3.0f))

        val result = DTW.computeDistance(seqA, seqB)
        assertTrue(result.isValid)
        assertEquals(0.0, result.accumulatedCost, 1e-6)
        assertEquals(0.0, result.normalizedDistance, 1e-6)
    }
}
