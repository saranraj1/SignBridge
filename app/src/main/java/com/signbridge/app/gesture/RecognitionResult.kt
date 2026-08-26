package com.signbridge.app.gesture

/**
 * Status of the gesture recognition match attempt.
 */
enum class MatchStatus {
    /** No enrolled prototypes exist in memory to compare against. */
    NO_PROTOTYPES,

    /** The live temporal buffer has not yet accumulated enough frames. */
    SEQUENCE_NOT_READY,

    /** Nearest prototype distance is less than or equal to threshold (ACCEPTED MATCH). */
    MATCH,

    /** Nearest prototype distance exceeds threshold (REJECTED / UNKNOWN GESTURE). */
    UNKNOWN
}

/**
 * Encapsulates the DTW comparison result between a live sequence and a specific prototype.
 */
data class MatchCandidate(
    val prototype: GesturePrototype,
    val dtwResult: DTWResult
)

/**
 * Comprehensive output of a 1-Nearest-Neighbor prototype matching query.
 *
 * @property bestMatch Closest gesture prototype (null if no prototypes exist or sequence not ready)
 * @property nearestDistance Distance to closest prototype (Double.POSITIVE_INFINITY if invalid)
 * @property threshold Applied distance acceptance threshold
 * @property isAccepted True if nearestDistance <= threshold
 * @property status Categorical match status
 * @property candidates Ranked list of all evaluated prototype candidates
 * @property totalLatencyMs Time taken to perform DTW matching across all prototypes in milliseconds
 */
data class RecognitionResult(
    val bestMatch: GesturePrototype?,
    val nearestDistance: Double,
    val threshold: Double,
    val isAccepted: Boolean,
    val status: MatchStatus,
    val candidates: List<MatchCandidate> = emptyList(),
    val totalLatencyMs: Double = 0.0
) {
    val recognizedLabel: String
        get() = when (status) {
            MatchStatus.MATCH -> bestMatch?.displayName ?: "UNKNOWN"
            MatchStatus.UNKNOWN -> "UNKNOWN"
            MatchStatus.SEQUENCE_NOT_READY -> "BUFFERING"
            MatchStatus.NO_PROTOTYPES -> "NO_PROTOTYPES"
        }

    companion object {
        fun sequenceNotReady(threshold: Double): RecognitionResult = RecognitionResult(
            bestMatch = null,
            nearestDistance = Double.POSITIVE_INFINITY,
            threshold = threshold,
            isAccepted = false,
            status = MatchStatus.SEQUENCE_NOT_READY
        )

        fun noPrototypes(threshold: Double): RecognitionResult = RecognitionResult(
            bestMatch = null,
            nearestDistance = Double.POSITIVE_INFINITY,
            threshold = threshold,
            isAccepted = false,
            status = MatchStatus.NO_PROTOTYPES
        )
    }
}
