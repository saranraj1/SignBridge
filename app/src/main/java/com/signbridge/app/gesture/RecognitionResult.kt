package com.signbridge.app.gesture

enum class MatchStatus { NO_HAND, NO_PROTOTYPES, SEQUENCE_NOT_READY, MATCH, UNKNOWN, AMBIGUOUS }

data class MatchCandidate(val prototype: GesturePrototype, val dtwResult: DTWResult)

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
            MatchStatus.NO_HAND -> "SEARCHING"
        }

    fun formatCandidateDistances(): String {
        if (candidates.isEmpty()) return "None"
        return candidates.joinToString(", ") {
            "${it.prototype.id}=${if (it.dtwResult.isValid) String.format("%.3f", it.dtwResult.normalizedDistance) else "INF"}"
        }
    }

    companion object {
        fun noHand(t: Double): RecognitionResult =
            RecognitionResult(null, Double.POSITIVE_INFINITY, threshold = t, isAccepted = false, status = MatchStatus.NO_HAND)

        fun noPrototypes(t: Double): RecognitionResult =
            RecognitionResult(null, Double.POSITIVE_INFINITY, threshold = t, isAccepted = false, status = MatchStatus.NO_PROTOTYPES)

        fun sequenceNotReady(t: Double): RecognitionResult =
            RecognitionResult(null, Double.POSITIVE_INFINITY, threshold = t, isAccepted = false, status = MatchStatus.SEQUENCE_NOT_READY)

        fun unknown(t: Double): RecognitionResult =
            RecognitionResult(null, Double.POSITIVE_INFINITY, threshold = t, isAccepted = false, status = MatchStatus.UNKNOWN)
    }
}
