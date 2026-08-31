package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import kotlin.math.sqrt

enum class SequenceStatus { EMPTY, FILLING, READY }

data class VelocityStatistics(
    val minVelocity: Float,
    val maxVelocity: Float,
    val meanVelocity: Float,
    val medianVelocity: Float,
    val velocityVariance: Float
) {
    fun formatSummary() = "min=${"%.4f".format(minVelocity)}, max=${"%.4f".format(maxVelocity)}, " +
            "mean=${"%.4f".format(meanVelocity)}, med=${"%.4f".format(medianVelocity)}, var=${"%.6f".format(velocityVariance)}"
}

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
    fun formatSummary() = "frames=$frameCount, dur=${durationMs}ms, hand=$dominantHandedness, " +
            "scale=${"%.3f".format(averageHandScale)}, x=[${"%.2f".format(minX)}..${"%.2f".format(maxX)}], " +
            "y=[${"%.2f".format(minY)}..${"%.2f".format(maxY)}], z=[${"%.2f".format(minZ)}..${"%.2f".format(maxZ)}], " +
            "motionVar=${"%.4f".format(motionVariance)}, checksum=${"%.4f".format(totalChecksum)}, vel=[${velocityStats.formatSummary()}]"
}

data class TemporalSequence(
    val frames: List<NormalizedLandmarkFrame>,
    val windowSize: Int = frames.size,
    val isReady: Boolean = frames.isNotEmpty(),
    val sequenceId: Long = 0L
) {
    val frameCount get() = frames.size
    val durationMs get() = if (frames.size > 1) frames.last().timestampMs - frames.first().timestampMs else 0L
    val oldestTimestampMs get() = frames.firstOrNull()?.timestampMs ?: 0L
    val newestTimestampMs get() = frames.lastOrNull()?.timestampMs ?: 0L

    /**
     * Production representation: Each frame contains 63-D hand shape plus 3-D cumulative wrist displacement.
     */
    fun toFeatureVectors66D(): List<FloatArray> {
        if (frames.isEmpty()) return emptyList()
        val baseWrist = frames.first().rawWristPosition
        val baseScale = frames.map { it.handScale }.sorted()[frames.size / 2].coerceAtLeast(1e-5f)
        return frames.map { frame ->
            val v = FloatArray(66)
            val shape = frame.toFeatureVector()
            System.arraycopy(shape, 0, v, 0, 63)
            v[63] = (frame.rawWristPosition.x - baseWrist.x) / baseScale
            v[64] = (frame.rawWristPosition.y - baseWrist.y) / baseScale
            v[65] = (frame.rawWristPosition.z - baseWrist.z) / baseScale
            v
        }
    }

    fun compute66DTotalChecksum(): Double {
        val vecs = toFeatureVectors66D()
        var sum = 0.0
        for (v in vecs) {
            for (x in v) sum += x.toDouble()
        }
        return sum
    }

    fun compute66DFrameChecksum(index: Int): Double {
        val vecs = toFeatureVectors66D()
        if (index !in vecs.indices) return 0.0
        var sum = 0.0
        for (x in vecs[index]) sum += x.toDouble()
        return sum
    }

    fun computeFrameChecksum(index: Int): Double = frames.getOrNull(index)?.let { f ->
        f.landmarks.sumOf { (it.x + it.y + it.z).toDouble() }
    } ?: 0.0

    fun computeTotalChecksum() = frames.sumOf { f -> f.landmarks.sumOf { (it.x + it.y + it.z).toDouble() } }

    fun computeFrameVelocities(): List<Float> = if (frames.size < 2) emptyList() else
        (1 until frames.size).map { computeInstantaneousVelocity(frames[it - 1], frames[it]) }

    fun computeVelocityStatistics(): VelocityStatistics {
        val v = computeFrameVelocities()
        if (v.isEmpty()) return VelocityStatistics(0f, 0f, 0f, 0f, 0f)
        val s = v.sorted()
        val mean = v.average().toFloat()
        val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
        val variance = v.map { (it - mean) * (it - mean) }.average().toFloat()
        return VelocityStatistics(s.first(), s.last(), mean, med, variance)
    }

    fun computeStatistics(): SequenceStatistics {
        if (frames.isEmpty()) return SequenceStatistics(0, 0, 0, 0, "None", 0f, 0f, 0f, 0f, 0f, 0f, 0f, false, 0f, 0.0, 0.0, 0.0, VelocityStatistics(0f, 0f, 0f, 0f, 0f))
        val pts = frames.flatMap { it.landmarks }
        val hand = if (frames.count { it.handedness.equals("Right", true) } * 2 >= frames.size) "Right" else "Left"

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
            frames.size,
            frames.first().timestampMs,
            frames.last().timestampMs,
            durationMs,
            hand,
            frames.map { it.handScale }.average().toFloat(),
            pts.minOf { it.x },
            pts.maxOf { it.x },
            pts.minOf { it.y },
            pts.maxOf { it.y },
            pts.minOf { it.z },
            pts.maxOf { it.z },
            pts.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() },
            motionVar,
            computeTotalChecksum(),
            computeFrameChecksum(0),
            computeFrameChecksum(frames.lastIndex),
            computeVelocityStatistics()
        )
    }

    fun formatMotionProfile(movingThreshold: Float = GestureConfig.MOTION_START_VELOCITY_THRESHOLD): String {
        if (frames.size < 2) return "S"
        val v = computeFrameVelocities()
        val sb = StringBuilder()
        sb.append(if (v.isNotEmpty() && v.first() >= movingThreshold) 'M' else 'S')
        v.forEach { sb.append(if (it >= movingThreshold) 'M' else 'S') }
        return sb.toString()
    }

    companion object {
        fun computeInstantaneousVelocity(prev: NormalizedLandmarkFrame, curr: NormalizedLandmarkFrame): Float {
            if (prev.landmarks.size != 21 || curr.landmarks.size != 21) return 0f
            var shape = 0.0
            for (i in 0 until 21) {
                val a = prev.landmarks[i]
                val b = curr.landmarks[i]
                val dx = (b.x - a.x).toDouble()
                val dy = (b.y - a.y).toDouble()
                val dz = (b.z - a.z).toDouble()
                shape += sqrt(dx * dx + dy * dy + dz * dz)
            }
            val finger = (shape / 21.0).toFloat()
            val scale = ((prev.handScale + curr.handScale) * 0.5f).coerceAtLeast(1e-5f)
            val wx = (curr.rawWristPosition.x - prev.rawWristPosition.x) / scale
            val wy = (curr.rawWristPosition.y - prev.rawWristPosition.y) / scale
            val wz = (curr.rawWristPosition.z - prev.rawWristPosition.z) / scale
            val wrist = sqrt(wx * wx + wy * wy + wz * wz)
            return finger + 0.5f * wrist
        }
    }

    fun resampleLinearly(targetLength: Int): List<FloatArray> {
        if (frames.isEmpty() || targetLength <= 0) return emptyList()
        val src = frames.map { it.toFeatureVector() }
        if (src.size == 1) return List(targetLength) { src[0].clone() }
        return resample(src, targetLength)
    }

    fun computeResampledEuclideanDistance(other: TemporalSequence, targetLength: Int = 20): Double {
        val a = resample(toFeatureVectors66D(), targetLength)
        val b = resample(other.toFeatureVectors66D(), targetLength)
        if (a.isEmpty() || b.isEmpty()) return Double.POSITIVE_INFINITY
        return a.indices.map { DTW.frameDistance(a[it], b[it]) }.average()
    }

    fun computeTrajectoryPearsonCorrelation(other: TemporalSequence, targetLength: Int = 20): Double {
        val a = resample(toFeatureVectors66D(), targetLength)
        val b = resample(other.toFeatureVectors66D(), targetLength)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var sum = 0.0
        var n = 0
        for (d in 63 until 66) {
            val x = a.map { it[d].toDouble() }
            val y = b.map { it[d].toDouble() }
            val mx = x.average()
            val my = y.average()
            var num = 0.0
            var dx = 0.0
            var dy = 0.0
            x.indices.forEach { i ->
                val xx = x[i] - mx
                val yy = y[i] - my
                num += xx * yy
                dx += xx * xx
                dy += yy * yy
            }
            if (dx > 1e-12 && dy > 1e-12) {
                sum += num / sqrt(dx * dy)
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun resample(src: List<FloatArray>, n: Int): List<FloatArray> {
        if (src.isEmpty() || n <= 0) return emptyList()
        if (src.size == 1) return List(n) { src[0].clone() }
        val out = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val p = i.toDouble() / (n - 1) * (src.size - 1)
            val lo = p.toInt()
            val hi = minOf(lo + 1, src.lastIndex)
            val f = (p - lo).toFloat()
            val v = FloatArray(src[0].size) { d -> src[lo][d] + f * (src[hi][d] - src[lo][d]) }
            out.add(v)
        }
        return out
    }
}
