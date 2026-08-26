package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Controlled experimental benchmark suite for SignBridge+ Milestone M3.
 *
 * Evaluates:
 * 1. Intra-gesture vs Inter-gesture distance separation
 * 2. Speed variation tolerance (DTW vs Naive Frame-by-Frame matching)
 * 3. Spatial scale and offset tolerance
 * 4. Generalization across simulated hand proportion variations
 */
class DTWExperimentTest {

    enum class BenchmarkGesture {
        PALM_WAVE,
        PINCH_TAP,
        SWIPE_UP
    }

    private fun generateGestureFrame(
        gesture: BenchmarkGesture,
        progress: Float, // 0.0f to 1.0f
        handVariation: Float = 1.0f,
        spatialOffset: Float = 0.0f
    ): NormalizedLandmarkFrame {
        val points = (0 until 21).map { i ->
            val baseX = (i * 0.05f + spatialOffset) * handVariation
            val baseY = (i * 0.03f + spatialOffset) * handVariation
            val baseZ = (i * 0.01f) * handVariation

            // Add gesture-specific motion trajectories
            val (dx, dy, dz) = when (gesture) {
                BenchmarkGesture.PALM_WAVE -> {
                    // Lateral sinusoidal waving motion
                    val wave = (sin(progress * 2 * Math.PI) * 0.4f).toFloat()
                    Triple(wave, 0.0f, 0.0f)
                }
                BenchmarkGesture.PINCH_TAP -> {
                    // Index (landmark 8) and Thumb (landmark 4) closing motion
                    val pinch = if (i in listOf(4, 8)) (1.0f - progress) * 0.3f else 0.0f
                    Triple(pinch, pinch, 0.0f)
                }
                BenchmarkGesture.SWIPE_UP -> {
                    // Vertical upward translation
                    Triple(0.0f, -progress * 0.5f, progress * 0.1f)
                }
            }

            NormalizedLandmarkPoint(baseX + dx, baseY + dy, baseZ + dz)
        }

        return NormalizedLandmarkFrame(
            timestampMs = (progress * 1000).toLong(),
            handedness = "Right",
            landmarks = points,
            handScale = 1.0f,
            rawWristPosition = LandmarkPoint(0.5f, 0.5f, 0.0f)
        )
    }

    private fun generateGestureSequence(
        gesture: BenchmarkGesture,
        frameCount: Int = 30,
        handVariation: Float = 1.0f,
        spatialOffset: Float = 0.0f
    ): TemporalSequence {
        val frames = (0 until frameCount).map { step ->
            val progress = step.toFloat() / (frameCount - 1).coerceAtLeast(1)
            generateGestureFrame(gesture, progress, handVariation, spatialOffset)
        }
        return TemporalSequence(frames = frames, windowSize = frameCount, isReady = true)
    }

    /**
     * EXPERIMENT 1: Intra-Gesture vs Inter-Gesture Distance Separation.
     * Validates that same gesture instances yield low distance while different gesture pairs yield high distance.
     */
    @Test
    fun experimentIntraVsInterGestureDistance() {
        val waveProto = generateGestureSequence(BenchmarkGesture.PALM_WAVE, frameCount = 30)
        val waveTest1 = generateGestureSequence(BenchmarkGesture.PALM_WAVE, frameCount = 30, spatialOffset = 0.02f)
        val waveTest2 = generateGestureSequence(BenchmarkGesture.PALM_WAVE, frameCount = 30, spatialOffset = 0.04f)

        val pinchProto = generateGestureSequence(BenchmarkGesture.PINCH_TAP, frameCount = 30)
        val swipeProto = generateGestureSequence(BenchmarkGesture.SWIPE_UP, frameCount = 30)

        val intraDist1 = DTW.computeDistance(waveProto, waveTest1).normalizedDistance
        val intraDist2 = DTW.computeDistance(waveProto, waveTest2).normalizedDistance
        val avgIntra = (intraDist1 + intraDist2) / 2.0

        val interDistPinch = DTW.computeDistance(waveProto, pinchProto).normalizedDistance
        val interDistSwipe = DTW.computeDistance(waveProto, swipeProto).normalizedDistance
        val avgInter = (interDistPinch + interDistSwipe) / 2.0

        println("=== EXPERIMENT 1: Intra vs Inter Gesture Distance ===")
        println("Avg Intra-Gesture Distance (WAVE vs WAVE): $avgIntra")
        println("Avg Inter-Gesture Distance (WAVE vs PINCH/SWIPE): $avgInter")
        println("Separation Margin: ${avgInter - avgIntra}")

        assertTrue("Intra-gesture distance must be substantially lower than inter-gesture distance", avgIntra < avgInter)
        assertTrue("Inter-gesture distance should be at least 3x larger than intra-gesture variation", avgInter > avgIntra * 3.0)
    }

    /**
     * EXPERIMENT 2: Speed Invariance (Fast vs Normal vs Slow).
     * Compares DTW vs Naive Frame-by-Frame Euclidean distance.
     */
    @Test
    fun experimentSpeedVariationTolerance() {
        val normalSeq = generateGestureSequence(BenchmarkGesture.SWIPE_UP, frameCount = 30) // Normal (30 frames)
        val fastSeq = generateGestureSequence(BenchmarkGesture.SWIPE_UP, frameCount = 15)   // Fast (15 frames)
        val slowSeq = generateGestureSequence(BenchmarkGesture.SWIPE_UP, frameCount = 45)   // Slow (45 frames)

        // DTW distances across speeds
        val dtwFast = DTW.computeDistance(normalSeq, fastSeq).normalizedDistance
        val dtwSlow = DTW.computeDistance(normalSeq, slowSeq).normalizedDistance

        // Naive frame-by-frame distance (resampled/truncated to min length)
        val minLen = minOf(normalSeq.frameCount, fastSeq.frameCount)
        var naiveFastSum = 0.0
        for (i in 0 until minLen) {
            naiveFastSum += DTW.frameDistance(normalSeq.frames[i].toFeatureVector(), fastSeq.frames[i].toFeatureVector())
        }
        val naiveFastDist = naiveFastSum / minLen

        println("=== EXPERIMENT 2: Speed Invariance ===")
        println("DTW Distance (Normal 30f vs Fast 15f): $dtwFast")
        println("DTW Distance (Normal 30f vs Slow 45f): $dtwSlow")
        println("Naive Frame-by-Frame Distance (Normal vs Fast): $naiveFastDist")

        assertTrue("DTW distance for speed variation should remain within reasonable bounds (< 1.5)", dtwFast < 1.5)
        assertTrue("DTW provides superior or comparable alignment for temporal scaling", dtwFast <= naiveFastDist + 0.1)
    }

    /**
     * EXPERIMENT 3: Generalization Across Hand Proportion Variations.
     */
    @Test
    fun experimentHandProportionVariation() {
        val personA = generateGestureSequence(BenchmarkGesture.PINCH_TAP, handVariation = 1.0f)
        val personB = generateGestureSequence(BenchmarkGesture.PINCH_TAP, handVariation = 1.15f) // 15% difference in finger proportions
        val differentGesture = generateGestureSequence(BenchmarkGesture.SWIPE_UP, handVariation = 1.15f)

        val samePersonDist = DTW.computeDistance(personA, personA).normalizedDistance
        val crossPersonDist = DTW.computeDistance(personA, personB).normalizedDistance
        val differentGestureDist = DTW.computeDistance(personA, differentGesture).normalizedDistance

        println("=== EXPERIMENT 3: Hand Variation Generalization ===")
        println("Same Hand Distance: $samePersonDist")
        println("Cross-Hand (Person A vs Person B same gesture): $crossPersonDist")
        println("Different Gesture (Person A PINCH vs Person B SWIPE): $differentGestureDist")

        assertTrue("Cross-hand same gesture distance must be lower than different gesture distance", crossPersonDist < differentGestureDist)
    }
}
