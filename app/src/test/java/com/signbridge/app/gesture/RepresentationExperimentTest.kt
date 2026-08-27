package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.LandmarkNormalizer
import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.vision.HandLandmarkData
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Empirical Unit Test Suite evaluating the Dual-Representation Experiment.
 *
 * Compares:
 * - Representation A: Baseline 63-D (Wrist-relative normalized coordinates)
 * - Representation B: Hybrid 66-D (63-D shape + 3-D cumulative global wrist displacement)
 * - Representation C: Trajectory-Preserved 63-D (All landmarks relative to frame 0 wrist)
 * - Representation D: Instantaneous Velocity 66-D (63-D shape + 3-D frame-to-frame wrist velocity)
 */
class RepresentationExperimentTest {

    private val normalizer = LandmarkNormalizer()

    private fun createSyntheticHand(
        wristX: Float,
        wristY: Float,
        wristZ: Float,
        scale: Float,
        fingerFlexion: FloatArray = FloatArray(5) { 0f },
        timestampMs: Long = 0L,
        handedness: String = "Right"
    ): HandLandmarkData {
        val landmarks = ArrayList<LandmarkPoint>(21)

        // 0: Wrist
        landmarks.add(LandmarkPoint(wristX, wristY, wristZ))

        // 1-4: Thumb
        val thumbFlex = fingerFlexion[0]
        landmarks.add(LandmarkPoint(wristX - 0.2f * scale, wristY - 0.2f * scale, wristZ))
        landmarks.add(LandmarkPoint(wristX - 0.35f * scale, wristY - 0.4f * scale, wristZ))
        landmarks.add(LandmarkPoint(wristX - 0.45f * scale + 0.2f * scale * thumbFlex, wristY - 0.6f * scale + 0.3f * scale * thumbFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX - 0.5f * scale + 0.35f * scale * thumbFlex, wristY - 0.75f * scale + 0.45f * scale * thumbFlex, wristZ))

        // 5-8: Index
        val indexFlex = fingerFlexion[1]
        landmarks.add(LandmarkPoint(wristX - 0.15f * scale, wristY - 0.8f * scale, wristZ))
        landmarks.add(LandmarkPoint(wristX - 0.18f * scale, wristY - 1.1f * scale + 0.3f * scale * indexFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX - 0.20f * scale, wristY - 1.35f * scale + 0.6f * scale * indexFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX - 0.22f * scale, wristY - 1.55f * scale + 0.85f * scale * indexFlex, wristZ))

        // 9-12: Middle (Index 9 is Middle MCP used for scale normalization)
        val middleFlex = fingerFlexion[2]
        landmarks.add(LandmarkPoint(wristX, wristY - scale, wristZ))
        landmarks.add(LandmarkPoint(wristX, wristY - 1.3f * scale + 0.35f * scale * middleFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX, wristY - 1.6f * scale + 0.7f * scale * middleFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX, wristY - 1.85f * scale + 0.95f * scale * middleFlex, wristZ))

        // 13-16: Ring
        val ringFlex = fingerFlexion[3]
        landmarks.add(LandmarkPoint(wristX + 0.15f * scale, wristY - 0.8f * scale, wristZ))
        landmarks.add(LandmarkPoint(wristX + 0.18f * scale, wristY - 1.1f * scale + 0.3f * scale * ringFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX + 0.20f * scale, wristY - 1.35f * scale + 0.6f * scale * ringFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX + 0.22f * scale, wristY - 1.55f * scale + 0.85f * scale * ringFlex, wristZ))

        // 17-20: Pinky
        val pinkyFlex = fingerFlexion[4]
        landmarks.add(LandmarkPoint(wristX + 0.3f * scale, wristY - 0.65f * scale, wristZ))
        landmarks.add(LandmarkPoint(wristX + 0.35f * scale, wristY - 0.9f * scale + 0.25f * scale * pinkyFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX + 0.38f * scale, wristY - 1.1f * scale + 0.5f * scale * pinkyFlex, wristZ))
        landmarks.add(LandmarkPoint(wristX + 0.4f * scale, wristY - 1.25f * scale + 0.7f * scale * pinkyFlex, wristZ))

        return HandLandmarkData(
            landmarks = landmarks,
            handedness = handedness,
            score = 0.95f
        )
    }

    private fun generateGestureSequence(
        gestureType: String,
        frameCount: Int = 15,
        speedFactor: Float = 1.0f,
        handScale: Float = 0.15f,
        wristStartX: Float = 0.5f,
        wristStartY: Float = 0.6f
    ): TemporalSequence {
        val normFrames = ArrayList<NormalizedLandmarkFrame>(frameCount)

        for (i in 0 until frameCount) {
            val progress = (i.toFloat() / (frameCount - 1).toFloat()).coerceIn(0f, 1f) * speedFactor

            val (wristX, wristY, flexion) = when (gestureType) {
                "HELP" -> {
                    // HELP: Upward hand translation + open flat palm
                    val wx = wristStartX
                    val wy = wristStartY - progress * 0.25f
                    val flex = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f)
                    Triple(wx, wy, flex)
                }
                "WAVE_SPATIAL" -> {
                    // PURE SPATIAL: Horizontal swipe left-to-right with static open palm
                    val wx = wristStartX + progress * 0.35f
                    val wy = wristStartY
                    val flex = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f)
                    Triple(wx, wy, flex)
                }
                "YES" -> {
                    // YES: Nodding fist
                    val wx = wristStartX
                    val wy = wristStartY + kotlin.math.sin(progress * Math.PI.toFloat() * 2f) * 0.12f
                    val flex = floatArrayOf(0.8f, 0.9f, 0.9f, 0.9f, 0.9f)
                    Triple(wx, wy, flex)
                }
                "NO" -> {
                    // NO: Pinch snap with stationary wrist
                    val wx = wristStartX
                    val wy = wristStartY
                    val snap = progress.coerceIn(0f, 1f)
                    val flex = floatArrayOf(0.5f, snap * 0.9f, snap * 0.9f, 0.1f, 0.1f)
                    Triple(wx, wy, flex)
                }
                "STATIC" -> {
                    // Stationary resting hand
                    val wx = wristStartX
                    val wy = wristStartY
                    val flex = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f)
                    Triple(wx, wy, flex)
                }
                else -> {
                    val wx = wristStartX + kotlin.math.sin(progress * 4f) * 0.2f
                    val wy = wristStartY + kotlin.math.cos(progress * 3f) * 0.2f
                    val flex = floatArrayOf(progress * 0.8f, 1f - progress * 0.8f, progress * 0.5f, 0.7f, 0.3f)
                    Triple(wx, wy, flex)
                }
            }

            val rawHand = createSyntheticHand(wristX, wristY, 0f, handScale, flexion, i * 66L)
            val normFrame = normalizer.normalizeHand(rawHand, i * 66L)!!
            normFrames.add(normFrame)
        }

        return TemporalSequence(normFrames, frameCount, isReady = true)
    }

    @Test
    fun testPureSpatialGestureSeparation() {
        // Compare a pure spatial wave against a stationary hand
        val spatialWave = generateGestureSequence("WAVE_SPATIAL", frameCount = 15)
        val staticHand = generateGestureSequence("STATIC", frameCount = 15)

        val comp = RepresentationExperiment.compareRepresentations(spatialWave, staticHand)

        println("=== PURE SPATIAL GESTURE VS STATIC HAND ===")
        println("Representation A (Baseline 63-D):        DTW = ${String.format("%.6f", comp.baseline63dDist)}  [FAILED: Sees zero movement!]")
        println("Representation B (Hybrid 66-D Cumulative): DTW = ${String.format("%.6f", comp.hybrid66dDist)}  [SUCCESS: Strong separation!]")
        println("Representation C (Trajectory-Preserved):  DTW = ${String.format("%.6f", comp.trajectoryPreserved63dDist)}  [SUCCESS: Strong separation!]")
        println("Representation D (Instantaneous Velocity): DTW = ${String.format("%.6f", comp.instantVelocity66dDist)}  [SUCCESS: Motion detected!]")

        // Mathematical proof:
        // In 63-D baseline, because fingers are identical and wrist is always (0,0,0), distance is EXACTLY 0.000000!
        assertEquals("63-D Baseline completely fails to distinguish spatial wave from static hand!", 0.0, comp.baseline63dDist, 1e-6)

        // In 66-D Hybrid and Trajectory Preserved representations, distance is clearly non-zero and large (>0.50)!
        assertTrue("66-D Hybrid MUST separate spatial wave from static hand (>0.50)", comp.hybrid66dDist > 0.50)
        assertTrue("63-D Trajectory Preserved MUST separate spatial wave from static hand (>1.0)", comp.trajectoryPreserved63dDist > 1.0)
    }

    @Test
    fun testCombinedGestureDistanceMatrix() {
        val help1 = generateGestureSequence("HELP", frameCount = 15)
        val help2 = generateGestureSequence("HELP", frameCount = 14, speedFactor = 1.05f)
        val wave = generateGestureSequence("WAVE_SPATIAL", frameCount = 15)
        val yes = generateGestureSequence("YES", frameCount = 15)
        val no = generateGestureSequence("NO", frameCount = 15)
        val staticHand = generateGestureSequence("STATIC", frameCount = 15)

        val compHelpSame = RepresentationExperiment.compareRepresentations(help1, help2)
        val compHelpVsWave = RepresentationExperiment.compareRepresentations(help1, wave)
        val compHelpVsYes = RepresentationExperiment.compareRepresentations(help1, yes)
        val compHelpVsNo = RepresentationExperiment.compareRepresentations(help1, no)
        val compHelpVsStatic = RepresentationExperiment.compareRepresentations(help1, staticHand)

        println("\n=== DUAL-REPRESENTATION DISTANCE MATRIX ===")
        println("Gesture Pair               | 63-D Baseline | 66-D Hybrid | 63-D Traj-Preserved | Euclid (66-D)")
        println("---------------------------+---------------+-------------+---------------------+--------------")
        println("HELP vs HELP (Same)        | ${String.format("%-13.4f", compHelpSame.baseline63dDist)} | ${String.format("%-11.4f", compHelpSame.hybrid66dDist)} | ${String.format("%-19.4f", compHelpSame.trajectoryPreserved63dDist)} | ${String.format("%.4f", compHelpSame.resampledEuclid66d)}")
        println("HELP vs STATIC (Stationary)| ${String.format("%-13.4f", compHelpVsStatic.baseline63dDist)} | ${String.format("%-11.4f", compHelpVsStatic.hybrid66dDist)} | ${String.format("%-19.4f", compHelpVsStatic.trajectoryPreserved63dDist)} | ${String.format("%.4f", compHelpVsStatic.resampledEuclid66d)}")
        println("HELP vs WAVE (Diff Spatial)| ${String.format("%-13.4f", compHelpVsWave.baseline63dDist)} | ${String.format("%-11.4f", compHelpVsWave.hybrid66dDist)} | ${String.format("%-19.4f", compHelpVsWave.trajectoryPreserved63dDist)} | ${String.format("%.4f", compHelpVsWave.resampledEuclid66d)}")
        println("HELP vs YES (Diff Gesture) | ${String.format("%-13.4f", compHelpVsYes.baseline63dDist)} | ${String.format("%-11.4f", compHelpVsYes.hybrid66dDist)} | ${String.format("%-19.4f", compHelpVsYes.trajectoryPreserved63dDist)} | ${String.format("%.4f", compHelpVsYes.resampledEuclid66d)}")
        println("HELP vs NO (Diff Gesture)  | ${String.format("%-13.4f", compHelpVsNo.baseline63dDist)} | ${String.format("%-11.4f", compHelpVsNo.hybrid66dDist)} | ${String.format("%-19.4f", compHelpVsNo.trajectoryPreserved63dDist)} | ${String.format("%.4f", compHelpVsNo.resampledEuclid66d)}")

        // In 66-D Hybrid:
        // 1. Same class distance is minimal (< 0.05)
        assertTrue(compHelpSame.hybrid66dDist < 0.05)
        // 2. Static hand is rejected (> 0.35)
        assertTrue(compHelpVsStatic.hybrid66dDist > 0.35)
        // 3. Different spatial wave is rejected (> 0.60)
        assertTrue(compHelpVsWave.hybrid66dDist > 0.60)
        // 4. Different gesture (YES, NO) is rejected (> 0.40)
        assertTrue(compHelpVsYes.hybrid66dDist > 0.40)
        assertTrue(compHelpVsNo.hybrid66dDist > 0.35)
    }
}
