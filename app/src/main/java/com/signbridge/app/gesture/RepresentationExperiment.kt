package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import kotlin.math.sqrt

/**
 * Diagnostic results from the Dual-Representation Experiment.
 */
data class DualRepresentationComparison(
    val baseline63dDist: Double,
    val hybrid66dDist: Double,
    val trajectoryPreserved63dDist: Double,
    val instantVelocity66dDist: Double,
    val resampledEuclid63d: Double,
    val resampledEuclid66d: Double,
    val lengthA: Int,
    val lengthB: Int
) {
    fun formatSummary(): String {
        return "63D_Base=${String.format("%.4f", baseline63dDist)} | " +
                "66D_Cumulative=${String.format("%.4f", hybrid66dDist)} | " +
                "63D_TrajPreserved=${String.format("%.4f", trajectoryPreserved63dDist)} | " +
                "66D_InstantVel=${String.format("%.4f", instantVelocity66dDist)} | " +
                "Euclid63D=${String.format("%.4f", resampledEuclid63d)} | " +
                "Euclid66D=${String.format("%.4f", resampledEuclid66d)} | " +
                "Len=[$lengthA vs $lengthB]"
    }
}

/**
 * Experimental dual-representation evaluator comparing the baseline 63-D wrist-normalized
 * representation against shape + spatial trajectory representations.
 */
object RepresentationExperiment {

    /**
     * Extracts Representation A (Baseline 63-D):
     * 21 normalized landmarks translated relative to wrist in each frame.
     */
    fun extractBaseline63D(sequence: TemporalSequence): List<FloatArray> {
        return sequence.frames.map { it.toFeatureVector() }
    }

    /**
     * Extracts Representation B (Hybrid 66-D: Shape + Cumulative Wrist Displacement):
     * 63-D normalized hand shape + 3-D cumulative wrist displacement relative to frame 0.
     */
    fun extractHybrid66DCumulative(sequence: TemporalSequence): List<FloatArray> {
        if (sequence.frames.isEmpty()) return emptyList()
        val baseWrist = sequence.frames.first().rawWristPosition

        return sequence.frames.map { frame ->
            val vec63 = frame.toFeatureVector()
            val vec66 = FloatArray(66)
            System.arraycopy(vec63, 0, vec66, 0, 63)

            val scale = if (frame.handScale > 1e-5f) frame.handScale else 1f
            vec66[63] = (frame.rawWristPosition.x - baseWrist.x) / scale
            vec66[64] = (frame.rawWristPosition.y - baseWrist.y) / scale
            vec66[65] = (frame.rawWristPosition.z - baseWrist.z) / scale

            vec66
        }
    }

    /**
     * Extracts Representation C (Trajectory-Preserving 63-D):
     * 21 landmarks translated relative to the FIRST frame's wrist position, scaled by current hand scale.
     */
    fun extractTrajectoryPreserved63D(sequence: TemporalSequence): List<FloatArray> {
        if (sequence.frames.isEmpty()) return emptyList()
        val baseWrist = sequence.frames.first().rawWristPosition

        return sequence.frames.map { frame ->
            val scale = if (frame.handScale > 1e-5f) frame.handScale else 1f
            // Frame wrist offset relative to base wrist
            val wristDx = (frame.rawWristPosition.x - baseWrist.x) / scale
            val wristDy = (frame.rawWristPosition.y - baseWrist.y) / scale
            val wristDz = (frame.rawWristPosition.z - baseWrist.z) / scale

            val vec63 = FloatArray(63)
            var idx = 0
            for (p in frame.landmarks) {
                // In normalized frame, p is relative to current wrist.
                // Adding current wrist offset relative to base gives position relative to base wrist!
                vec63[idx++] = p.x + wristDx
                vec63[idx++] = p.y + wristDy
                vec63[idx++] = p.z + wristDz
            }
            vec63
        }
    }

    /**
     * Extracts Representation D (Hybrid 66-D: Shape + Instantaneous Wrist Velocity):
     * 63-D normalized hand shape + 3-D instantaneous frame-to-frame wrist velocity.
     */
    fun extractInstantVelocity66D(sequence: TemporalSequence): List<FloatArray> {
        if (sequence.frames.isEmpty()) return emptyList()

        return sequence.frames.mapIndexed { idx, frame ->
            val vec63 = frame.toFeatureVector()
            val vec66 = FloatArray(66)
            System.arraycopy(vec63, 0, vec66, 0, 63)

            val scale = if (frame.handScale > 1e-5f) frame.handScale else 1f
            if (idx > 0) {
                val prev = sequence.frames[idx - 1]
                vec66[63] = (frame.rawWristPosition.x - prev.rawWristPosition.x) / scale
                vec66[64] = (frame.rawWristPosition.y - prev.rawWristPosition.y) / scale
                vec66[65] = (frame.rawWristPosition.z - prev.rawWristPosition.z) / scale
            } else {
                vec66[63] = 0f
                vec66[64] = 0f
                vec66[65] = 0f
            }
            vec66
        }
    }

    /**
     * Computes the side-by-side comparison across all representations for two sequences.
     */
    fun compareRepresentations(seqA: TemporalSequence, seqB: TemporalSequence): DualRepresentationComparison {
        val baseA = extractBaseline63D(seqA)
        val baseB = extractBaseline63D(seqB)
        val dtwBase = DTW.computeDistance(baseA, baseB).normalizedDistance

        val hybridA = extractHybrid66DCumulative(seqA)
        val hybridB = extractHybrid66DCumulative(seqB)
        val dtwHybrid = DTW.computeDistance(hybridA, hybridB).normalizedDistance

        val trajA = extractTrajectoryPreserved63D(seqA)
        val trajB = extractTrajectoryPreserved63D(seqB)
        val dtwTraj = DTW.computeDistance(trajA, trajB).normalizedDistance

        val instA = extractInstantVelocity66D(seqA)
        val instB = extractInstantVelocity66D(seqB)
        val dtwInst = DTW.computeDistance(instA, instB).normalizedDistance

        val euclid63 = seqA.computeResampledEuclideanDistance(seqB, 20)
        val euclid66 = computeResampledEuclidean(hybridA, hybridB, 20)

        return DualRepresentationComparison(
            baseline63dDist = dtwBase,
            hybrid66dDist = dtwHybrid,
            trajectoryPreserved63dDist = dtwTraj,
            instantVelocity66dDist = dtwInst,
            resampledEuclid63d = euclid63,
            resampledEuclid66d = euclid66,
            lengthA = seqA.frameCount,
            lengthB = seqB.frameCount
        )
    }

    private fun computeResampledEuclidean(vecsA: List<FloatArray>, vecsB: List<FloatArray>, targetLength: Int): Double {
        if (vecsA.isEmpty() || vecsB.isEmpty() || targetLength <= 0) return Double.POSITIVE_INFINITY
        val resA = resampleVectorsLinearly(vecsA, targetLength)
        val resB = resampleVectorsLinearly(vecsB, targetLength)

        var sum = 0.0
        for (i in 0 until targetLength) {
            sum += DTW.frameDistance(resA[i], resB[i])
        }
        return sum / targetLength.toDouble()
    }

    private fun resampleVectorsLinearly(vectors: List<FloatArray>, targetLength: Int): List<FloatArray> {
        if (vectors.isEmpty()) return emptyList()
        if (vectors.size == 1) return List(targetLength) { vectors[0].clone() }

        val dim = vectors[0].size
        val result = ArrayList<FloatArray>(targetLength)
        val origLen = vectors.size

        for (i in 0 until targetLength) {
            val progress = i.toDouble() / (targetLength - 1).toDouble()
            val origIndexExact = progress * (origLen - 1)
            val idxLow = origIndexExact.toInt().coerceIn(0, origLen - 1)
            val idxHigh = (idxLow + 1).coerceAtMost(origLen - 1)
            val frac = (origIndexExact - idxLow).toFloat()

            val vecLow = vectors[idxLow]
            val vecHigh = vectors[idxHigh]
            val interpolated = FloatArray(dim)
            for (d in 0 until dim) {
                interpolated[d] = vecLow[d] + frac * (vecHigh[d] - vecLow[d])
            }
            result.add(interpolated)
        }

        return result
    }
}
