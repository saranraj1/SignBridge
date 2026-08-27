package com.signbridge.app.gesture

import kotlin.math.sqrt

/**
 * Pure Kotlin mathematical implementation of Dynamic Time Warping (DTW).
 *
 * Computes the optimal temporal alignment and cumulative Euclidean distance between two
 * 66-dimensional feature sequences (63-D normalized hand shape + 3-D cumulative wrist displacement)
 * of arbitrary duration.
 */
object DTW {

    /**
     * Computes the DTW distance between two [TemporalSequence] instances using the production 66-D representation.
     *
     * @param sequenceA First temporal sequence
     * @param sequenceB Second temporal sequence
     * @return [DTWResult] containing accumulated cost and length-normalized distance
     */
    fun computeDistance(sequenceA: TemporalSequence, sequenceB: TemporalSequence): DTWResult {
        val vectorsA = sequenceA.toFeatureVectors66D()
        val vectorsB = sequenceB.toFeatureVectors66D()
        return computeDistance(vectorsA, vectorsB)
    }

    /**
     * Computes the DTW distance between two lists of feature vectors.
     *
     * @param seqA Sequence A of frame feature vectors
     * @param seqB Sequence B of frame feature vectors
     * @return [DTWResult] with accumulated cost and normalized path distance
     */
    fun computeDistance(seqA: List<FloatArray>, seqB: List<FloatArray>): DTWResult {
        val startTimeNs = System.nanoTime()

        val n = seqA.size
        val m = seqB.size

        // Edge case: both sequences empty
        if (n == 0 && m == 0) {
            val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000.0
            return DTWResult(
                accumulatedCost = 0.0,
                normalizedDistance = 0.0,
                sequenceALength = 0,
                sequenceBLength = 0,
                computationTimeMs = elapsedMs
            )
        }

        // Edge case: one sequence empty while other is non-empty
        if (n == 0 || m == 0) {
            return DTWResult.INVALID
        }

        // Initialize (N+1) x (M+1) cost matrix with infinity
        val dtwMatrix = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        dtwMatrix[0][0] = 0.0

        // Fill accumulated cost matrix
        for (i in 1..n) {
            val vecA = seqA[i - 1]
            for (j in 1..m) {
                val vecB = seqB[j - 1]
                val cost = frameDistance(vecA, vecB)

                if (!cost.isFinite()) {
                    return DTWResult.INVALID
                }

                val minPrev = minOf(
                    dtwMatrix[i - 1][j],     // Insertion (vertical step)
                    dtwMatrix[i][j - 1],     // Deletion (horizontal step)
                    dtwMatrix[i - 1][j - 1]  // Match (diagonal step)
                )

                dtwMatrix[i][j] = cost + minPrev
            }
        }

        val totalAccumulatedCost = dtwMatrix[n][m]
        val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000.0

        if (!totalAccumulatedCost.isFinite()) {
            return DTWResult.INVALID
        }

        // Normalization: average frame deviation across total sequence lengths (N + M)
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
     * Computes the Euclidean distance between two feature vectors of arbitrary identical dimension.
     *
     * @param f1 First frame feature vector
     * @param f2 Second frame feature vector
     * @return Euclidean distance, or Double.POSITIVE_INFINITY if invalid/mismatched
     */
    fun frameDistance(f1: FloatArray, f2: FloatArray): Double {
        if (f1.size != f2.size || f1.isEmpty()) {
            return Double.POSITIVE_INFINITY
        }

        var sumSq = 0.0
        for (i in f1.indices) {
            val v1 = f1[i]
            val v2 = f2[i]

            if (!v1.isFinite() || !v2.isFinite()) {
                return Double.POSITIVE_INFINITY
            }

            val diff = (v1 - v2).toDouble()
            sumSq += diff * diff
        }

        return sqrt(sumSq)
    }
}
