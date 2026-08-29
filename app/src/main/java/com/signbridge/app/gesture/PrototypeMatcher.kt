package com.signbridge.app.gesture

/**
 * 1-Nearest Neighbor prototype matcher with class-level ambiguity resolution.
 */
class PrototypeMatcher {
    private val prototypes = mutableListOf<GesturePrototype>()

    @Synchronized
    fun addPrototype(p: GesturePrototype) {
        prototypes.removeAll { it.id == p.id }
        prototypes.add(p)
    }

    @Synchronized
    fun removePrototype(id: String) = prototypes.removeAll { it.id == id }

    @Synchronized
    fun clearPrototypes() {
        prototypes.clear()
    }

    @Synchronized
    fun getPrototypes(): List<GesturePrototype> = prototypes.toList()

    fun match(
        live: TemporalSequence,
        threshold: Double = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD,
        ambiguityMargin: Double = GestureConfig.DEFAULT_AMBIGUITY_MARGIN
    ): RecognitionResult {
        val ps = getPrototypes()
        if (ps.isEmpty()) return RecognitionResult.noPrototypes(threshold)
        if (!live.isReady || live.frameCount < GestureConfig.MIN_GESTURE_DURATION_FRAMES) return RecognitionResult.sequenceNotReady(threshold)

        val start = System.nanoTime()
        val candidates = ps.map { p -> MatchCandidate(p, DTW.computeDistance(live, p.sequence)) }.filter { it.dtwResult.isValid }
        if (candidates.isEmpty()) return RecognitionResult.unknown(threshold)

        val grouped = candidates.groupBy { it.prototype.displayName.lowercase() }.map { (_, cs) ->
            val sorted = cs.sortedBy { it.dtwResult.normalizedDistance }
            val classScore = sorted[0].dtwResult.normalizedDistance
            GestureClassCandidate(sorted[0], classScore, sorted)
        }.sortedBy { it.score }

        val best = grouped.first()
        val runner = grouped.getOrNull(1)
        val margin = runner?.let { it.score - best.score } ?: Double.POSITIVE_INFINITY
        val accepted = best.score <= threshold && (runner == null || margin >= ambiguityMargin)
        val status = when {
            best.score > threshold -> MatchStatus.UNKNOWN
            runner != null && margin < ambiguityMargin -> MatchStatus.AMBIGUOUS
            else -> MatchStatus.MATCH
        }
        val ranked = candidates.sortedBy { it.dtwResult.normalizedDistance }
        return RecognitionResult(
            bestMatch = best.match.prototype,
            nearestDistance = best.score,
            runnerUpMatch = runner?.match?.prototype,
            runnerUpDistance = runner?.score ?: Double.POSITIVE_INFINITY,
            margin = if (margin.isFinite()) margin else 0.0,
            threshold = threshold,
            isAccepted = accepted,
            status = status,
            candidates = ranked,
            totalLatencyMs = (System.nanoTime() - start) / 1_000_000.0
        )
    }

    private data class GestureClassCandidate(val match: MatchCandidate, val score: Double, val members: List<MatchCandidate>)
}
