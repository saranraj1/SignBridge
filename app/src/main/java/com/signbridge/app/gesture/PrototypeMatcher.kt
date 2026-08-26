package com.signbridge.app.gesture

import java.util.Collections

/**
 * 1-Nearest-Neighbor (1-NN) Prototype Matcher using Dynamic Time Warping (DTW)
 * with calibrated distance thresholding and ambiguity margin gating.
 */
class PrototypeMatcher {

    private val prototypeList = Collections.synchronizedList(mutableListOf<GesturePrototype>())

    val prototypeCount: Int
        get() = prototypeList.size

    /**
     * Registers a new gesture prototype in memory.
     */
    fun addPrototype(prototype: GesturePrototype) {
        synchronized(prototypeList) {
            prototypeList.removeAll { it.id == prototype.id }
            prototypeList.add(prototype)
        }
    }

    /**
     * Removes a prototype by its ID.
     */
    fun removePrototype(id: String): Boolean {
        return synchronized(prototypeList) {
            prototypeList.removeAll { it.id == id }
        }
    }

    /**
     * Clears all enrolled prototypes from memory.
     */
    fun clearPrototypes() {
        synchronized(prototypeList) {
            prototypeList.clear()
        }
    }

    /**
     * Returns an immutable copy of currently enrolled prototypes.
     */
    fun getPrototypes(): List<GesturePrototype> {
        return synchronized(prototypeList) {
            ArrayList(prototypeList)
        }
    }

    /**
     * Matches a live temporal sequence against all enrolled prototypes using DTW.
     *
     * @param liveSequence Live sequence snapshot from the temporal buffer
     * @param threshold Maximum allowable normalized DTW distance (default: [GestureConfig.DEFAULT_RECOGNITION_THRESHOLD])
     * @param ambiguityMargin Minimum margin required over runner-up class (default: [GestureConfig.DEFAULT_AMBIGUITY_MARGIN])
     * @return [RecognitionResult] containing nearest match, runner-up, margin, and verdict
     */
    fun match(
        liveSequence: TemporalSequence,
        threshold: Double = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD,
        ambiguityMargin: Double = GestureConfig.DEFAULT_AMBIGUITY_MARGIN
    ): RecognitionResult {
        val currentPrototypes = getPrototypes()

        if (currentPrototypes.isEmpty()) {
            return RecognitionResult.noPrototypes(threshold)
        }

        if (!liveSequence.isReady || liveSequence.frameCount < 5) {
            return RecognitionResult.sequenceNotReady(threshold)
        }

        val startTimeNs = System.nanoTime()
        val candidates = mutableListOf<MatchCandidate>()

        var bestCandidate: MatchCandidate? = null
        var minDistance = Double.POSITIVE_INFINITY

        for (proto in currentPrototypes) {
            val dtwResult = DTW.computeDistance(liveSequence, proto.sequence)
            val candidate = MatchCandidate(proto, dtwResult)
            candidates.add(candidate)

            if (dtwResult.isValid && dtwResult.normalizedDistance < minDistance) {
                minDistance = dtwResult.normalizedDistance
                bestCandidate = candidate
            }
        }

        // Identify closest runner-up from a DIFFERENT gesture class to compute ambiguity margin
        var runnerUpCandidate: MatchCandidate? = null
        var runnerUpDistance = Double.POSITIVE_INFINITY

        if (bestCandidate != null) {
            val bestLabel = bestCandidate.prototype.displayName
            for (cand in candidates) {
                if (cand.prototype.displayName != bestLabel && cand.dtwResult.isValid) {
                    if (cand.dtwResult.normalizedDistance < runnerUpDistance) {
                        runnerUpDistance = cand.dtwResult.normalizedDistance
                        runnerUpCandidate = cand
                    }
                }
            }
        }

        val totalLatencyMs = (System.nanoTime() - startTimeNs) / 1_000_000.0
        val margin = if (runnerUpDistance.isFinite() && minDistance.isFinite()) {
            runnerUpDistance - minDistance
        } else {
            Double.POSITIVE_INFINITY
        }

        // Decision logic: Threshold Gating + Ambiguity Gating
        val passesThreshold = bestCandidate != null && minDistance <= threshold
        val isAmbiguous = passesThreshold && runnerUpCandidate != null && margin < ambiguityMargin

        val status = when {
            bestCandidate == null || minDistance.isInfinite() -> MatchStatus.UNKNOWN
            !passesThreshold -> MatchStatus.UNKNOWN
            isAmbiguous -> MatchStatus.AMBIGUOUS
            else -> MatchStatus.MATCH
        }

        val isAccepted = status == MatchStatus.MATCH

        return RecognitionResult(
            bestMatch = bestCandidate?.prototype,
            nearestDistance = minDistance,
            runnerUpMatch = runnerUpCandidate?.prototype,
            runnerUpDistance = runnerUpDistance,
            margin = if (margin.isInfinite()) 0.0 else margin,
            threshold = threshold,
            isAccepted = isAccepted,
            status = status,
            candidates = candidates.sortedBy { it.dtwResult.normalizedDistance },
            totalLatencyMs = totalLatencyMs
        )
    }
}
