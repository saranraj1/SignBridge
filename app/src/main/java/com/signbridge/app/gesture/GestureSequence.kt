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
 * @property windowSize Configured maximum capacity of the sequence window
 * @property isReady True when `frames.size == windowSize`
 * @property sequenceId Monotonically increasing ID identifying this specific temporal window instance
 */
data class TemporalSequence(
    val frames: List<NormalizedLandmarkFrame>,
    val windowSize: Int,
    val isReady: Boolean,
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
}
