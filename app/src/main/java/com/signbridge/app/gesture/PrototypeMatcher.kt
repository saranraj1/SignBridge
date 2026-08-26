package com.signbridge.app.gesture

import java.util.Collections

/**
 * 1-Nearest-Neighbor (1-NN) Prototype Matcher using Dynamic Time Warping (DTW).
 *
 * Compares live 30-frame temporal sequence snapshots against enrolled in-memory gesture prototypes
 * and applies distance threshold gating to classify as MATCH vs UNKNOWN.
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
            // Replace if existing ID matches, otherwise append
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
     * @param threshold Maximum allowable normalized DTW distance to consider a match valid
     * @return [RecognitionResult] containing nearest match, distances, and MATCH / UNKNOWN verdict
     */
    fun match(
        liveSequence: TemporalSequence,
        threshold: Double = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD
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

        val totalLatencyMs = (System.nanoTime() - startTimeNs) / 1_000_000.0

        val isAccepted = bestCandidate != null && minDistance <= threshold
        val status = if (bestCandidate == null || minDistance.isInfinite()) {
            MatchStatus.UNKNOWN
        } else if (isAccepted) {
            MatchStatus.MATCH
        } else {
            MatchStatus.UNKNOWN
        }

        return RecognitionResult(
            bestMatch = bestCandidate?.prototype,
            nearestDistance = minDistance,
            threshold = threshold,
            isAccepted = isAccepted,
            status = status,
            candidates = candidates.sortedBy { it.dtwResult.normalizedDistance },
            totalLatencyMs = totalLatencyMs
        )
    }
}
