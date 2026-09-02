package com.signbridge.app.gesture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure Kotlin mathematical implementation of Dynamic Time Warping (DTW).
 *
 * Computes the optimal temporal alignment and cumulative Euclidean distance between two
 * 66-dimensional feature sequences (63-D normalized hand shape + 3-D cumulative wrist displacement).
 *
 * FIX #4: Added Sakoe-Chiba band constraint.
 * Without a warping band, DTW can align any frame of sequence A to any frame of sequence B,
 * meaning a 10-frame gesture can "match" a 90-frame sequence by warping all frames to a
 * single column. On real devices this produces false positives across unrelated gestures.
 * The band limits alignment to within ±bandWidth frames of the diagonal, where
 * bandWidth = ceil(max(N, M) * DTW_SAKOE_CHIBA_BAND_RATIO).
 */
object DTW {

    fun computeDistance(a: TemporalSequence, b: TemporalSequence): DTWResult =
        computeDistance(a.toFeatureVectors66D(), b.toFeatureVectors66D())

    fun computeDistance(seqA: List<FloatArray>, seqB: List<FloatArray>): DTWResult {
        val startTimeNs = System.nanoTime()

        val n = seqA.size
        val m = seqB.size

        if (n == 0 && m == 0) {
            val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000.0
            return DTWResult(0.0, 0.0, 0, 0, elapsedMs)
        }

        if (n == 0 || m == 0) {
            return DTWResult.INVALID
        }

        // FIX #4: Sakoe-Chiba band width. 0 means no band (full matrix).
        // Must be at least |N - M| + 2 so the endpoint (N, M) is reachable for multi-speed variations.
        val bandWidth = if (GestureConfig.DTW_SAKOE_CHIBA_BAND_RATIO > 0f) {
            maxOf(abs(n - m) + 2, (maxOf(n, m) * GestureConfig.DTW_SAKOE_CHIBA_BAND_RATIO).toInt())
        } else {
            Int.MAX_VALUE
        }

        // (N+1) x (M+1) cost matrix initialised to infinity
        val dtwMatrix = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        dtwMatrix[0][0] = 0.0

        for (i in 1..n) {
            val vecA = seqA[i - 1]
            // FIX #4: Only fill cells within the Sakoe-Chiba band
            val jLo = maxOf(1, i - bandWidth)
            val jHi = minOf(m, i + bandWidth)
            for (j in jLo..jHi) {
                val cost = frameDistance(vecA, seqB[j - 1])
                if (!cost.isFinite()) return DTWResult.INVALID

                val minPrev = minOf(
                    dtwMatrix[i - 1][j],
                    dtwMatrix[i][j - 1],
                    dtwMatrix[i - 1][j - 1]
                )
                dtwMatrix[i][j] = cost + minPrev
            }
        }

        val totalAccumulatedCost = dtwMatrix[n][m]
        val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000.0

        if (!totalAccumulatedCost.isFinite()) {
            return DTWResult.INVALID
        }

        val normalizedDist = totalAccumulatedCost / (n + m).toDouble()

        return DTWResult(
            accumulatedCost = totalAccumulatedCost,
            normalizedDistance = normalizedDist,
            sequenceALength = n,
            sequenceBLength = m,
            computationTimeMs = elapsedMs
        )
    }

    /**
     * Computes the Euclidean distance across matching vector dimensions (66-D or arbitrary).
     */
    fun frameDistance(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return Double.POSITIVE_INFINITY

        var sumSq = 0.0
        for (i in a.indices) {
            val v1 = a[i]
            val v2 = b[i]
            if (!v1.isFinite() || !v2.isFinite()) return Double.POSITIVE_INFINITY
            val diff = (v1 - v2).toDouble()
            sumSq += diff * diff
        }
        return sqrt(sumSq)
    }
}
