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
    val motionVariance: Float
) {
    fun formatSummary(): String {
        return "frames=$frameCount, dur=${durationMs}ms, hand=$dominantHandedness, scale=${String.format("%.3f", averageHandScale)}, " +
                "x=[${String.format("%.2f", minX)}..${String.format("%.2f", maxX)}], " +
                "y=[${String.format("%.2f", minY)}..${String.format("%.2f", maxY)}], " +
                "z=[${String.format("%.2f", minZ)}..${String.format("%.2f", maxZ)}], " +
                "motionVar=${String.format("%.4f", motionVariance)}, valid=$allValidLandmarks"
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
                motionVariance = 0f
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
            motionVariance = motionVar
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
     * 'S' = Stationary (velocity < movingThreshold)
     * 'M' = Moving (velocity >= movingThreshold)
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

    companion object {
        /**
         * Computes the instantaneous landmark velocity (mean Euclidean coordinate displacement)
         * between two normalized landmark frames.
         *
         * Delta L(t, t-1) = 1/21 * sum_{i=0..20} sqrt((x_i^t - x_i^{t-1})^2 + (y_i^t - y_i^{t-1})^2 + (z_i^t - z_i^{t-1})^2)
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
            return (sumDist / 21.0).toFloat()
        }
    }
}
