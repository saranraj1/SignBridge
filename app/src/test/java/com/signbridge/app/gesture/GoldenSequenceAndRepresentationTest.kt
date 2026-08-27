package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.LandmarkNormalizer
import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.HandLandmarkData
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Rigorous mathematical and empirical test suite investigating the feature representation,
 * DTW properties, persistence, self-matching, and raw vs normalized motion.
 *
 * Fulfills:
 * - Section 2: Golden Sequence Test
 * - Section 3: Persistence Round-Trip
 * - Section 4: Teach Sample Consistency Matrix
 * - Section 6: Critical Representation Metrics
 * - Section 7: Raw vs Normalized Landmark Motion Loss
 * - Section 8: Static Hand vs Gesture Separation
 * - Section 9: Inter-Class Separation (HELP vs YES vs NO vs RANDOM)
 */
class GoldenSequenceAndRepresentationTest {

    private val normalizer = LandmarkNormalizer()

    /**
     * Synthesizes a realistic hand pose.
     * Wrist is at (wristX, wristY, wristZ).
     * Middle MCP is at (wristX, wristY - scale, wristZ).
     * Finger tips have specific articulation offsets based on gesture type.
     */
    private fun createSyntheticHand(
        wristX: Float,
        wristY: Float,
        wristZ: Float,
        scale: Float,
        fingerFlexion: FloatArray = FloatArray(5) { 0f }, // Flexion for Thumb, Index, Middle, Ring, Pinky [0.0 = open, 1.0 = closed]
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
        landmarks.add(LandmarkPoint(wristX, wristY - scale, wristZ)) // 9: Middle MCP
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

    /**
     * Synthesizes a temporal sequence of normalized landmark frames for a dynamic gesture.
     */
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
                    // HELP: Palm opens flat, then moves upward while thumb stays out
                    val wx = wristStartX
                    val wy = wristStartY - progress * 0.25f // moving upward
                    val flex = floatArrayOf(
                        0.1f, // thumb open
                        progress * 0.1f,
                        progress * 0.1f,
                        progress * 0.1f,
                        progress * 0.1f
                    )
                    Triple(wx, wy, flex)
                }
                "YES" -> {
                    // YES: Fist nodding up and down
                    val wx = wristStartX
                    val wy = wristStartY + kotlin.math.sin(progress * Math.PI.toFloat() * 2f) * 0.15f
                    val flex = floatArrayOf(0.8f, 0.9f, 0.9f, 0.9f, 0.9f) // fist
                    Triple(wx, wy, flex)
                }
                "NO" -> {
                    // NO: Index and middle snap to thumb (pinch)
                    val wx = wristStartX
                    val wy = wristStartY
                    val snap = progress.coerceIn(0f, 1f)
                    val flex = floatArrayOf(0.5f, snap * 0.9f, snap * 0.9f, 0.1f, 0.1f)
                    Triple(wx, wy, flex)
                }
                "STATIC" -> {
                    // Stationary resting hand (no movement)
                    val wx = wristStartX
                    val wy = wristStartY
                    val flex = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f)
                    Triple(wx, wy, flex)
                }
                else -> { // RANDOM
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

    // =========================================================================
    // SECTION 2: GOLDEN SEQUENCE TEST
    // =========================================================================

    @Test
    fun testGoldenSequenceSelfMatchIsExactZero() {
        val helpP1 = generateGestureSequence("HELP", frameCount = 15)

        // 1. DTW(P1, P1) MUST BE 0.000000
        val selfDtw = DTW.computeDistance(helpP1, helpP1)
        assertEquals("DTW distance of sequence to itself must be exactly 0.0", 0.0, selfDtw.normalizedDistance, 1e-6)
        assertEquals("Accumulated cost of sequence to itself must be 0.0", 0.0, selfDtw.accumulatedCost, 1e-6)

        // 2. PrototypeMatcher with P1 MUST MATCH P1 with distance 0
        val matcher = PrototypeMatcher()
        val protoP1 = GesturePrototype("HELP_1", "HELP", helpP1)
        matcher.addPrototype(protoP1)

        val result = matcher.match(helpP1, threshold = 0.32)
        assertEquals("Matcher must classify P1 as MATCH", MatchStatus.MATCH, result.status)
        assertEquals("Recognized label must be HELP", "HELP", result.recognizedLabel)
        assertEquals("Nearest distance must be 0.0", 0.0, result.nearestDistance, 1e-6)
    }

    // =========================================================================
    // SECTION 3: PERSISTENCE ROUND-TRIP FIDELITY
    // =========================================================================

    @Test
    fun testPersistenceRoundTripFidelity() {
        val originalSeq = generateGestureSequence("HELP", frameCount = 18)
        val originalProto = GesturePrototype("HELP_P1", "HELP", originalSeq)
        val profile = GestureProfile("profile_help", "HELP", listOf(originalProto))

        // Convert profile to JSON string (same format as GestureProfileStorage)
        val json = GestureProfileStorage.serializeProfiles(listOf(profile))
        val restoredProfiles = GestureProfileStorage.deserializeProfiles(json)

        assertEquals(1, restoredProfiles.size)
        val restoredProfile = restoredProfiles[0]
        val restoredProto = restoredProfile.prototypes[0]
        val restoredSeq = restoredProto.sequence

        // 1. DTW(original, restored) MUST BE 0.000000
        val roundTripDtw = DTW.computeDistance(originalSeq, restoredSeq)
        assertEquals("Round-trip DTW distance must be 0.0", 0.0, roundTripDtw.normalizedDistance, 1e-6)

        // 2. DTW(restored, restored) MUST BE 0.000000
        val restoredSelfDtw = DTW.computeDistance(restoredSeq, restoredSeq)
        assertEquals("Restored self-match DTW must be 0.0", 0.0, restoredSelfDtw.normalizedDistance, 1e-6)

        // 3. Matcher with restored prototype recognizes original sequence
        val matcher = PrototypeMatcher()
        matcher.addPrototype(restoredProto)
        val result = matcher.match(originalSeq, threshold = 0.32)
        assertEquals(MatchStatus.MATCH, result.status)
        assertEquals("HELP", result.recognizedLabel)
        assertEquals(0.0, result.nearestDistance, 1e-6)
    }

    // =========================================================================
    // SECTION 4: TEACH SAMPLE CONSISTENCY MATRIX
    // =========================================================================

    @Test
    fun testTeachSampleConsistencyMatrix() {
        // Generate 3 realistic demonstrations of the same HELP gesture with natural timing variation
        val p1 = generateGestureSequence("HELP", frameCount = 14, speedFactor = 0.95f)
        val p2 = generateGestureSequence("HELP", frameCount = 16, speedFactor = 1.05f)
        val p3 = generateGestureSequence("HELP", frameCount = 15, speedFactor = 1.00f)

        // 1. Self distances
        val d11 = DTW.computeDistance(p1, p1).normalizedDistance
        val d22 = DTW.computeDistance(p2, p2).normalizedDistance
        val d33 = DTW.computeDistance(p3, p3).normalizedDistance

        assertEquals(0.0, d11, 1e-6)
        assertEquals(0.0, d22, 1e-6)
        assertEquals(0.0, d33, 1e-6)

        // 2. Intra-pair distances
        val d12 = DTW.computeDistance(p1, p2).normalizedDistance
        val d13 = DTW.computeDistance(p1, p3).normalizedDistance
        val d23 = DTW.computeDistance(p2, p3).normalizedDistance

        println("=== TEACH SAMPLE CONSISTENCY MATRIX ===")
        println("P1(14p) vs P2(16p): DTW = ${String.format("%.4f", d12)}")
        println("P1(14p) vs P3(15p): DTW = ${String.format("%.4f", d13)}")
        println("P2(16p) vs P3(15p): DTW = ${String.format("%.4f", d23)}")

        // Natural variations of the same gesture should have low DTW distance (< 0.15)
        assertTrue("Intra-prototype distance P1-P2 must be small (<0.15)", d12 < 0.15)
        assertTrue("Intra-prototype distance P1-P3 must be small (<0.15)", d13 < 0.15)
        assertTrue("Intra-prototype distance P2-P3 must be small (<0.15)", d23 < 0.15)
    }

    // =========================================================================
    // SECTION 7: RAW VS NORMALIZED LANDMARKS — GLOBAL MOTION ANALYSIS
    // =========================================================================

    @Test
    fun testRawVsNormalizedMotionAnalysis() {
        // SCENARIO A: PURE GLOBAL TRANSLATION (Moving static hand from X=0.3 to X=0.7)
        // Fingers remain frozen in flat palm pose.
        val translationFramesRaw = ArrayList<HandLandmarkData>()
        val translationFramesNorm = ArrayList<NormalizedLandmarkFrame>()

        for (i in 0 until 10) {
            val progress = i.toFloat() / 9f
            val rawHand = createSyntheticHand(
                wristX = 0.3f + progress * 0.4f, // moving 0.4 across screen
                wristY = 0.5f,
                wristZ = 0f,
                scale = 0.15f,
                fingerFlexion = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f) // frozen flat palm
            )
            translationFramesRaw.add(rawHand)
            translationFramesNorm.add(normalizer.normalizeHand(rawHand, i * 66L)!!)
        }

        // Measure raw wrist displacement
        val rawWristDisplacement = translationFramesRaw.last().landmarks[0].x - translationFramesRaw.first().landmarks[0].x
        // Measure normalized wrist displacement (wrist is index 0)
        val normWristDisplacement = translationFramesNorm.last().landmarks[0].x - translationFramesNorm.first().landmarks[0].x
        // Measure normalized sequence velocity
        val normSeq = TemporalSequence(translationFramesNorm, 10, true)
        val normMeanVel = normSeq.computeVelocityStatistics().meanVelocity

        println("=== RAW VS NORMALIZED TRANSLATION ANALYSIS ===")
        println("Raw Wrist X Displacement: ${String.format("%.4f", rawWristDisplacement)} (Screen Space)")
        println("Normalized Wrist X Displacement: ${String.format("%.4f", normWristDisplacement)} (Wrist-relative)")
        println("Normalized Mean Velocity: ${String.format("%.6f", normMeanVel)}")

        // Mathematical proof:
        // Because normalized wrist is ALWAYS (0,0,0), normalized wrist displacement is 0.0.
        assertEquals(0.0f, normWristDisplacement, 1e-6f)
        // Because fingers did not move relative to wrist, normalized landmark velocity is near 0.0.
        assertTrue("Normalized velocity of pure translation is near zero (<0.001)", normMeanVel < 0.001f)

        // SCENARIO B: PURE FINGER ARTICULATION (Stationary wrist, fingers flexing into fist)
        val articulationFramesRaw = ArrayList<HandLandmarkData>()
        val articulationFramesNorm = ArrayList<NormalizedLandmarkFrame>()

        for (i in 0 until 10) {
            val progress = i.toFloat() / 9f
            val rawHand = createSyntheticHand(
                wristX = 0.5f, // stationary wrist
                wristY = 0.5f,
                wristZ = 0f,
                scale = 0.15f,
                fingerFlexion = FloatArray(5) { progress } // flexing from 0.0 to 1.0
            )
            articulationFramesRaw.add(rawHand)
            articulationFramesNorm.add(normalizer.normalizeHand(rawHand, i * 66L)!!)
        }

        val artNormSeq = TemporalSequence(articulationFramesNorm, 10, true)
        val artNormMeanVel = artNormSeq.computeVelocityStatistics().meanVelocity

        println("Normalized Articulation Velocity: ${String.format("%.4f", artNormMeanVel)}")
        assertTrue("Finger articulation produces strong normalized velocity (>0.03)", artNormMeanVel > 0.03f)
    }

    // =========================================================================
    // SECTION 8 & 9: INTER-CLASS SEPARATION (HELP vs YES vs NO vs STATIC)
    // =========================================================================

    @Test
    fun testInterClassSeparationMatrix() {
        val help = generateGestureSequence("HELP", frameCount = 15)
        val yes = generateGestureSequence("YES", frameCount = 15)
        val no = generateGestureSequence("NO", frameCount = 15)
        val staticHand = generateGestureSequence("STATIC", frameCount = 15)

        // Intra-class comparison (HELP variant vs HELP)
        val helpVariant = generateGestureSequence("HELP", frameCount = 14, speedFactor = 1.05f)
        val distHelpToHelp = DTW.computeDistance(help, helpVariant).normalizedDistance

        // Inter-class distances
        val distHelpToYes = DTW.computeDistance(help, yes).normalizedDistance
        val distHelpToNo = DTW.computeDistance(help, no).normalizedDistance
        val distHelpToStatic = DTW.computeDistance(help, staticHand).normalizedDistance
        val distYesToNo = DTW.computeDistance(yes, no).normalizedDistance
        val distYesToStatic = DTW.computeDistance(yes, staticHand).normalizedDistance

        println("=== INTER-CLASS DTW DISTANCE MATRIX ===")
        println("HELP vs HELP (variant):     DTW = ${String.format("%.4f", distHelpToHelp)}")
        println("HELP vs STATIC (stationary): DTW = ${String.format("%.4f", distHelpToStatic)}")
        println("HELP vs YES:                DTW = ${String.format("%.4f", distHelpToYes)}")
        println("HELP vs NO:                 DTW = ${String.format("%.4f", distHelpToNo)}")
        println("YES vs NO:                  DTW = ${String.format("%.4f", distYesToNo)}")
        println("YES vs STATIC:              DTW = ${String.format("%.4f", distYesToStatic)}")

        // Validation criteria:
        // 1. HELP vs HELP distance MUST be significantly smaller than HELP vs other gestures
        assertTrue("HELP-HELP (${distHelpToHelp}) must be < HELP-STATIC (${distHelpToStatic})", distHelpToHelp < distHelpToStatic)
        assertTrue("HELP-HELP (${distHelpToHelp}) must be < HELP-YES (${distHelpToYes})", distHelpToHelp < distHelpToYes)
        assertTrue("HELP-HELP (${distHelpToHelp}) must be < HELP-NO (${distHelpToNo})", distHelpToHelp < distHelpToNo)

        // 2. Inter-class separation margin must be >= 0.10
        val separationMargin = distHelpToYes - distHelpToHelp
        assertTrue("Separation margin between classes must be >= 0.10 (actual: $separationMargin)", separationMargin >= 0.10)
    }

    // =========================================================================
    // SECTION 6 & 9: DTW VS RESAMPLED EUCLIDEAN VS CORRELATION
    // =========================================================================

    @Test
    fun testDTWVsResampledEuclideanVsCorrelation() {
        val help1 = generateGestureSequence("HELP", frameCount = 18)
        val help2 = generateGestureSequence("HELP", frameCount = 12, speedFactor = 1.1f)
        val yes = generateGestureSequence("YES", frameCount = 15)

        val dtwSame = DTW.computeDistance(help1, help2).normalizedDistance
        val dtwDiff = DTW.computeDistance(help1, yes).normalizedDistance

        val euclidSame = help1.computeResampledEuclideanDistance(help2, 20)
        val euclidDiff = help1.computeResampledEuclideanDistance(yes, 20)

        val corrSame = help1.computeTrajectoryPearsonCorrelation(help2, 20)
        val corrDiff = help1.computeTrajectoryPearsonCorrelation(yes, 20)

        println("=== DISTANCE METRIC COMPARISON ===")
        println("Metric            | Same Class (HELP vs HELP) | Diff Class (HELP vs YES)")
        println("------------------+---------------------------+--------------------------")
        println("DTW Distance      | ${String.format("%-25.4f", dtwSame)} | ${String.format("%-24.4f", dtwDiff)}")
        println("Resampled Euclid  | ${String.format("%-25.4f", euclidSame)} | ${String.format("%-24.4f", euclidDiff)}")
        println("Pearson Corr      | ${String.format("%-25.4f", corrSame)} | ${String.format("%-24.4f", corrDiff)}")

        assertTrue("DTW same class must be lower than different class", dtwSame < dtwDiff)
        assertTrue("Euclidean same class must be lower than different class", euclidSame < euclidDiff)
        assertTrue("Correlation same class must be higher than different class", corrSame > corrDiff)
    }
}
