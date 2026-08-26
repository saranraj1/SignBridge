package com.signbridge.app.gesture

/**
 * Encapsulates the output of a Dynamic Time Warping (DTW) alignment computation.
 *
 * @property accumulatedCost Total unnormalized minimum cumulative cost along the optimal warping path
 * @property normalizedDistance Length-normalized distance (accumulatedCost / (sequenceALength + sequenceBLength))
 * @property sequenceALength Number of frames in first sequence
 * @property sequenceBLength Number of frames in second sequence
 * @property computationTimeMs Time taken to execute the DTW algorithm in milliseconds
 */
data class DTWResult(
    val accumulatedCost: Double,
    val normalizedDistance: Double,
    val sequenceALength: Int,
    val sequenceBLength: Int,
    val computationTimeMs: Double
) {
    val isValid: Boolean
        get() = !normalizedDistance.isNaN() && !normalizedDistance.isInfinite() && normalizedDistance >= 0.0

    companion object {
        val INVALID = DTWResult(
            accumulatedCost = Double.POSITIVE_INFINITY,
            normalizedDistance = Double.POSITIVE_INFINITY,
            sequenceALength = 0,
            sequenceBLength = 0,
            computationTimeMs = 0.0
        )
    }
}
