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
    UNKNOWN,

    /** Top two distinct gesture classes are too close in distance (AMBIGUOUS / REJECTED). */
    AMBIGUOUS
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
 * @property runnerUpMatch Closest prototype belonging to a different gesture class (null if only one class exists)
 * @property runnerUpDistance Distance to closest prototype of different class
 * @property margin Difference between runnerUpDistance and nearestDistance
 * @property threshold Applied distance acceptance threshold
 * @property isAccepted True if nearestDistance <= threshold and not ambiguous
 * @property status Categorical match status
 * @property candidates Ranked list of all evaluated prototype candidates
 * @property totalLatencyMs Time taken to perform DTW matching across all prototypes in milliseconds
 */
data class RecognitionResult(
    val bestMatch: GesturePrototype?,
    val nearestDistance: Double,
    val runnerUpMatch: GesturePrototype? = null,
    val runnerUpDistance: Double = Double.POSITIVE_INFINITY,
    val margin: Double = 0.0,
    val threshold: Double,
    val isAccepted: Boolean,
    val status: MatchStatus,
    val candidates: List<MatchCandidate> = emptyList(),
    val totalLatencyMs: Double = 0.0
) {
    val recognizedLabel: String
        get() = when (status) {
            MatchStatus.MATCH -> bestMatch?.displayName ?: "UNKNOWN"
            MatchStatus.UNKNOWN,
            MatchStatus.AMBIGUOUS -> "UNKNOWN"
            MatchStatus.SEQUENCE_NOT_READY -> "BUFFERING"
            MatchStatus.NO_PROTOTYPES -> "NO_PROTOTYPES"
        }

    /**
     * Formatted string showing all prototype distances for debug logging.
     * Example: "HELP_P1=0.12, HELP_P2=0.15, YES_P1=0.78, YES_P2=0.82"
     */
    fun formatCandidateDistances(): String {
        if (candidates.isEmpty()) return "None"
        return candidates.joinToString(", ") { c ->
            "${c.prototype.id}=${if (c.dtwResult.isValid) String.format("%.2f", c.dtwResult.normalizedDistance) else "INF"}"
        }
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
