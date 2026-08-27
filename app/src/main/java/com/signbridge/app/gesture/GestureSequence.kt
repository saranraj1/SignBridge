package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import kotlin.math.sqrt

/**
 * Status of the rolling temporal gesture buffer.
 */
enum class SequenceStatus {
    /** Buffer contains 0 frames. */
    EMPTY,

    /** Buffer contains frames but has not reached full window capacity yet. */
    FILLING,

    /** Buffer has reached full window capacity (e.g. 30 frames) and is ready for recognition. */
    READY
}

/**
 * Deep statistical summary of frame-to-frame velocity metrics.
 */
data class VelocityStatistics(
    val minVelocity: Float,
    val maxVelocity: Float,
    val meanVelocity: Float,
    val medianVelocity: Float,
    val velocityVariance: Float
) {
    fun formatSummary(): String {
        return "min=${String.format("%.4f", minVelocity)}, " +
                "max=${String.format("%.4f", maxVelocity)}, " +
                "mean=${String.format("%.4f", meanVelocity)}, " +
                "med=${String.format("%.4f", medianVelocity)}, " +
                "var=${String.format("%.6f", velocityVariance)}"
    }
}

/**
 * Comprehensive descriptive statistics of a [TemporalSequence].
 */
data class SequenceStatistics(
    val frameCount: Int,
    val firstTimestampMs: Long,
    val lastTimestampMs: Long,
    val durationMs: Long,
    val dominantHandedness: String,
    val averageHandScale: Float,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
    val allValidLandmarks: Boolean,
    val motionVariance: Float,
    val totalChecksum: Double,
    val firstFrameChecksum: Double,
    val lastFrameChecksum: Double,
    val velocityStats: VelocityStatistics
) {
    fun formatSummary(): String {
        return "frames=$frameCount, dur=${durationMs}ms, hand=$dominantHandedness, scale=${String.format("%.3f", averageHandScale)}, " +
                "x=[${String.format("%.2f", minX)}..${String.format("%.2f", maxX)}], " +
                "y=[${String.format("%.2f", minY)}..${String.format("%.2f", maxY)}], " +
                "z=[${String.format("%.2f", minZ)}..${String.format("%.2f", maxZ)}], " +
                "motionVar=${String.format("%.4f", motionVariance)}, " +
                "checksum=${String.format("%.4f", totalChecksum)}, " +
                "f0Check=${String.format("%.4f", firstFrameChecksum)}, " +
                "fNCheck=${String.format("%.4f", lastFrameChecksum)}, " +
                "vel=[${velocityStats.formatSummary()}]"
    }
}

/**
 * Immutable snapshot of a temporal sequence of normalized landmark frames.
 *
 * @property frames Ordered list of frames from oldest to newest (chronological order)
 * @property windowSize Configured maximum capacity of the sequence window (optional for variable-length sequences)
 * @property isReady True when sequence has sufficient frames for evaluation
 * @property sequenceId Monotonically increasing ID identifying this specific temporal window instance
 */
data class TemporalSequence(
    val frames: List<NormalizedLandmarkFrame>,
    val windowSize: Int = frames.size,
    val isReady: Boolean = frames.isNotEmpty(),
    val sequenceId: Long = 0L
) {
    val frameCount: Int
        get() = frames.size

    val durationMs: Long
        get() = if (frames.size >= 2) {
            frames.last().timestampMs - frames.first().timestampMs
        } else {
            0L
        }

    val oldestTimestampMs: Long
        get() = if (frames.isNotEmpty()) frames.first().timestampMs else 0L

    val newestTimestampMs: Long
        get() = if (frames.isNotEmpty()) frames.last().timestampMs else 0L

    /**
     * Extracts the production 66-dimensional feature vectors:
     * - 63-D normalized hand shape (wrist-relative normalized coordinates)
     * - 3-D cumulative global wrist displacement relative to frame 0: (wrist(t) - wrist(0)) / scale(t)
     */
    fun toFeatureVectors66D(): List<FloatArray> {
        if (frames.isEmpty()) return emptyList()
        val baseWrist = frames.first().rawWristPosition

        return frames.map { frame ->
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
     * Computes the coordinate checksum across all landmarks in a single frame.
     */
    fun computeFrameChecksum(frameIndex: Int): Double {
        if (frameIndex !in frames.indices) return 0.0
        var sum = 0.0
        for (p in frames[frameIndex].landmarks) {
            sum += p.x.toDouble() + p.y.toDouble() + p.z.toDouble()
        }
        return sum
    }

    /**
     * Computes the global coordinate checksum across all frames and landmarks.
     */
    fun computeTotalChecksum(): Double {
        var sum = 0.0
        for (f in frames) {
            for (p in f.landmarks) {
                sum += p.x.toDouble() + p.y.toDouble() + p.z.toDouble()
            }
        }
        return sum
    }

    /**
     * Computes statistical velocity metrics (min, max, mean, median, variance).
     */
    fun computeVelocityStatistics(): VelocityStatistics {
        val velocities = computeFrameVelocities()
        if (velocities.isEmpty()) {
            return VelocityStatistics(0f, 0f, 0f, 0f, 0f)
        }

        val sorted = velocities.sorted()
        val minV = sorted.first()
        val maxV = sorted.last()
        val meanV = sorted.average().toFloat()
        val medianV = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        }

        var varianceSum = 0.0
        for (v in velocities) {
            val diff = (v - meanV).toDouble()
            varianceSum += diff * diff
        }
        val varianceV = (varianceSum / velocities.size).toFloat()

        return VelocityStatistics(
            minVelocity = minV,
            maxVelocity = maxV,
            meanVelocity = meanV,
            medianVelocity = medianV,
            velocityVariance = varianceV
        )
    }

    /**
     * Computes deep descriptive statistics across all frames and landmarks in the sequence.
     */
    fun computeStatistics(): SequenceStatistics {
        if (frames.isEmpty()) {
            return SequenceStatistics(
                frameCount = 0,
                firstTimestampMs = 0L,
                lastTimestampMs = 0L,
                durationMs = 0L,
                dominantHandedness = "None",
                averageHandScale = 0f,
                minX = 0f,
                maxX = 0f,
                minY = 0f,
                maxY = 0f,
                minZ = 0f,
                maxZ = 0f,
                allValidLandmarks = false,
                motionVariance = 0f,
                totalChecksum = 0.0,
                firstFrameChecksum = 0.0,
                lastFrameChecksum = 0.0,
                velocityStats = VelocityStatistics(0f, 0f, 0f, 0f, 0f)
            )
        }

        val firstTs = frames.first().timestampMs
        val lastTs = frames.last().timestampMs
        val dur = if (frames.size >= 2) lastTs - firstTs else 0L

        // Dominant handedness
        val rightCount = frames.count { it.handedness.equals("Right", ignoreCase = true) }
        val handedness = if (rightCount >= frames.size / 2) "Right" else "Left"

        val avgScale = frames.map { it.handScale }.average().toFloat()

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var allValid = true

        for (f in frames) {
            if (f.landmarks.size != 21) {
                allValid = false
            }

            for (p in f.landmarks) {
                if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite()) {
                    allValid = false
                }
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
                if (p.z < minZ) minZ = p.z
                if (p.z > maxZ) maxZ = p.z
            }
        }

        // Compute temporal motion variance of normalized landmarks across all 21 points
        var totalVariance = 0.0
        for (landmarkIndex in 0 until 21) {
            val xValues = frames.mapNotNull { if (it.landmarks.size > landmarkIndex) it.landmarks[landmarkIndex].x else null }
            val yValues = frames.mapNotNull { if (it.landmarks.size > landmarkIndex) it.landmarks[landmarkIndex].y else null }
            if (xValues.isNotEmpty() && yValues.isNotEmpty()) {
                val meanX = xValues.average()
                val meanY = yValues.average()
                val varX = xValues.map { (it - meanX) * (it - meanX) }.average()
                val varY = yValues.map { (it - meanY) * (it - meanY) }.average()
                totalVariance += (varX + varY)
            }
        }
        val motionVar = sqrt(totalVariance / 21.0).toFloat()

        return SequenceStatistics(
            frameCount = frames.size,
            firstTimestampMs = firstTs,
            lastTimestampMs = lastTs,
            durationMs = dur,
            dominantHandedness = handedness,
            averageHandScale = avgScale,
            minX = if (minX.isInfinite()) 0f else minX,
            maxX = if (maxX.isInfinite()) 0f else maxX,
            minY = if (minY.isInfinite()) 0f else minY,
            maxY = if (maxY.isInfinite()) 0f else maxY,
            minZ = if (minZ.isInfinite()) 0f else minZ,
            maxZ = if (maxZ.isInfinite()) 0f else maxZ,
            allValidLandmarks = allValid,
            motionVariance = motionVar,
            totalChecksum = computeTotalChecksum(),
            firstFrameChecksum = computeFrameChecksum(0),
            lastFrameChecksum = computeFrameChecksum(frames.size - 1),
            velocityStats = computeVelocityStatistics()
        )
    }

    /**
     * Computes the list of frame-to-frame instantaneous landmark velocities across the sequence.
     */
    fun computeFrameVelocities(): List<Float> {
        if (frames.size < 2) return emptyList()
        val velocities = ArrayList<Float>(frames.size - 1)
        for (i in 1 until frames.size) {
            velocities.add(computeInstantaneousVelocity(frames[i - 1], frames[i]))
        }
        return velocities
    }

    /**
     * Generates a character-level motion profile string for the sequence (e.g. "SSSSMMMMMSSSS").
     */
    fun formatMotionProfile(movingThreshold: Float = 0.035f): String {
        val velocities = computeFrameVelocities()
        if (velocities.isEmpty()) return "S"
        val sb = StringBuilder()
        sb.append(if (velocities.first() >= movingThreshold) 'M' else 'S')
        for (v in velocities) {
            sb.append(if (v >= movingThreshold) 'M' else 'S')
        }
        return sb.toString()
    }

    /**
     * Resamples this sequence linearly to a fixed number of frames (63-D vectors).
     */
    fun resampleLinearly(targetLength: Int): List<FloatArray> {
        if (frames.isEmpty() || targetLength <= 0) return emptyList()
        if (frames.size == 1) {
            val singleVec = frames[0].toFeatureVector()
            return List(targetLength) { singleVec.clone() }
        }

        val vectors = frames.map { it.toFeatureVector() }
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
            val interpolated = FloatArray(63)
            for (d in 0 until 63) {
                interpolated[d] = vecLow[d] + frac * (vecHigh[d] - vecLow[d])
            }
            result.add(interpolated)
        }

        return result
    }

    /**
     * Computes frame-to-frame Euclidean distance after linear temporal resampling.
     */
    fun computeResampledEuclideanDistance(other: TemporalSequence, targetLength: Int = 20): Double {
        if (this.frames.isEmpty() || other.frames.isEmpty()) return Double.POSITIVE_INFINITY
        val resampledA = this.resampleLinearly(targetLength)
        val resampledB = other.resampleLinearly(targetLength)

        var totalDist = 0.0
        for (i in 0 until targetLength) {
            totalDist += DTW.frameDistance(resampledA[i], resampledB[i])
        }
        return totalDist / targetLength.toDouble()
    }

    /**
     * Computes the mean Pearson correlation coefficient between landmark coordinate trajectories.
     */
    fun computeTrajectoryPearsonCorrelation(other: TemporalSequence, targetLength: Int = 20): Double {
        if (this.frames.isEmpty() || other.frames.isEmpty()) return 0.0
        val resampledA = this.resampleLinearly(targetLength)
        val resampledB = other.resampleLinearly(targetLength)

        var sumCorr = 0.0
        var validDims = 0

        for (d in 0 until 63) {
            val valsA = DoubleArray(targetLength) { i -> resampledA[i][d].toDouble() }
            val valsB = DoubleArray(targetLength) { i -> resampledB[i][d].toDouble() }

            val meanA = valsA.average()
            val meanB = valsB.average()

            var num = 0.0
            var denA = 0.0
            var denB = 0.0

            for (i in 0 until targetLength) {
                val diffA = valsA[i] - meanA
                val diffB = valsB[i] - meanB
                num += diffA * diffB
                denA += diffA * diffA
                denB += diffB * diffB
            }

            val denom = sqrt(denA * denB)
            if (denom > 1e-6) {
                sumCorr += num / denom
                validDims++
            }
        }

        return if (validDims > 0) sumCorr / validDims else 0.0
    }

    companion object {
        /**
         * Computes the instantaneous landmark velocity (mean Euclidean coordinate displacement)
         * between two normalized landmark frames, accounting for both finger articulation and spatial wrist movement.
         */
        fun computeInstantaneousVelocity(
            prevFrame: NormalizedLandmarkFrame,
            currFrame: NormalizedLandmarkFrame
        ): Float {
            if (prevFrame.landmarks.size != 21 || currFrame.landmarks.size != 21) {
                return 0f
            }

            var sumDist = 0.0
            for (i in 0 until 21) {
                val p1 = prevFrame.landmarks[i]
                val p2 = currFrame.landmarks[i]
                val dx = (p2.x - p1.x).toDouble()
                val dy = (p2.y - p1.y).toDouble()
                val dz = (p2.z - p1.z).toDouble()
                sumDist += sqrt(dx * dx + dy * dy + dz * dz)
            }
            val fingerVel = (sumDist / 21.0).toFloat()

            // Wrist displacement in camera space scaled by hand size
            val scale = if (currFrame.handScale > 1e-5f) currFrame.handScale else 1f
            val wdx = (currFrame.rawWristPosition.x - prevFrame.rawWristPosition.x) / scale
            val wdy = (currFrame.rawWristPosition.y - prevFrame.rawWristPosition.y) / scale
            val wdz = (currFrame.rawWristPosition.z - prevFrame.rawWristPosition.z) / scale
            val wristVel = sqrt(wdx * wdx + wdy * wdy + wdz * wdz).toFloat()

            return fingerVel + 0.5f * wristVel
        }
    }
}
