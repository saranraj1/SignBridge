package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Milestone M4 Controlled Few-Shot Recognition & Generalization Experiment Suite.
 */
class FewShotEvaluationTest {

    enum class TestGesture {
        HELP_SIGN,
        YES_SIGN,
        NO_SIGN,
        CUSTOM_NOVEL_SIGN
    }

    private fun generateGestureFrame(
        gesture: TestGesture,
        progress: Float,
        handVariation: Float = 1.0f,
        noiseOffset: Float = 0.0f
    ): NormalizedLandmarkFrame {
        val points = (0 until 21).map { i ->
            val baseX = (i * 0.04f + noiseOffset) * handVariation
            val baseY = (i * 0.03f + noiseOffset) * handVariation
            val baseZ = (i * 0.01f) * handVariation

            val (dx, dy, dz) = when (gesture) {
                TestGesture.HELP_SIGN -> {
                    // Open flat hand moving upward
                    Triple(0.0f, -progress * 0.4f, progress * 0.05f)
                }
                TestGesture.YES_SIGN -> {
                    // Nodding fist motion (vertical oscillation)
                    val nod = (sin(progress * 2 * Math.PI) * 0.25f).toFloat()
                    Triple(0.0f, nod, 0.0f)
                }
                TestGesture.NO_SIGN -> {
                    // Lateral side-to-side waving
                    val shake = (sin(progress * 2 * Math.PI) * 0.35f).toFloat()
                    Triple(shake, 0.0f, 0.0f)
                }
                TestGesture.CUSTOM_NOVEL_SIGN -> {
                    // Custom diagonal inward trajectory
                    Triple(progress * 0.3f, progress * 0.3f, -progress * 0.1f)
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

    private fun generateSequence(
        gesture: TestGesture,
        frameCount: Int = 30,
        handVariation: Float = 1.0f,
        noiseOffset: Float = 0.0f
    ): TemporalSequence {
        val frames = (0 until frameCount).map { step ->
            val progress = step.toFloat() / (frameCount - 1).coerceAtLeast(1)
            generateGestureFrame(gesture, progress, handVariation, noiseOffset)
        }
        return TemporalSequence(frames = frames, windowSize = frameCount, isReady = true)
    }

    /**
     * EXPERIMENT 1: 3-Shot Enrollment + Multi-Gesture Recognition Benchmark.
     * Enrolls 3 gestures (HELP, YES, NO) with 3 shots each (9 prototypes).
     * Tests each gesture with 5 distinct test samples (15 total test trials).
     */
    @Test
    fun experimentThreeShotRecognitionBenchmark() {
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        // Enroll 3 gestures with 3 demonstrations each
        val gestures = listOf(TestGesture.HELP_SIGN, TestGesture.YES_SIGN, TestGesture.NO_SIGN)
        for (g in gestures) {
            val enrollmentSeqs = (1..3).map { shot ->
                generateSequence(g, frameCount = 30, noiseOffset = shot * 0.015f)
            }
            store.createProfile(g.name, enrollmentSeqs)
        }

        // Load into matcher
        for (proto in store.getAllPrototypes()) {
            matcher.addPrototype(proto)
        }

        assertEquals(3, store.profileCount)
        assertEquals(9, store.totalPrototypeCount)

        // Evaluate 5 test samples per gesture (15 total queries)
        var correctMatches = 0
        var totalTrials = 0

        for (targetGesture in gestures) {
            for (trial in 1..5) {
                totalTrials++
                // Distinct test instance with independent noise
                val testSeq = generateSequence(targetGesture, frameCount = 30, noiseOffset = 0.02f + trial * 0.005f)
                val result = matcher.match(testSeq, threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)

                if (result.status == MatchStatus.MATCH && result.recognizedLabel == targetGesture.name) {
                    correctMatches++
                }
            }
        }

        val accuracy = (correctMatches.toDouble() / totalTrials) * 100.0
        println("=== EXPERIMENT 1: 3-Shot Multi-Gesture Recognition Benchmark ===")
        println("Total Enrolled Gestures: 3 (${store.totalPrototypeCount} prototypes)")
        println("Total Test Trials: $totalTrials")
        println("Correct Matches: $correctMatches / $totalTrials ($accuracy%)")

        assertEquals(15, totalTrials)
        assertTrue("3-Shot recognition accuracy should be >= 90%", accuracy >= 90.0)
    }

    /**
     * EXPERIMENT 2: Novel Gesture Learning Without Code Modifications.
     * Enrolls a completely custom, newly invented gesture and immediately tests recognition.
     */
    @Test
    fun experimentNovelGestureLearning() {
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        // Enroll standard gestures
        store.createProfile("HELP", (1..3).map { generateSequence(TestGesture.HELP_SIGN, noiseOffset = it * 0.01f) })
        store.createProfile("YES", (1..3).map { generateSequence(TestGesture.YES_SIGN, noiseOffset = it * 0.01f) })

        // User invents and teaches a completely novel gesture: "MY_CUSTOM_SIGN"
        val novelDemos = (1..3).map {
            generateSequence(TestGesture.CUSTOM_NOVEL_SIGN, noiseOffset = it * 0.01f)
        }
        val novelProfile = store.createProfile("MY_CUSTOM_SIGN", novelDemos)

        for (proto in store.getAllPrototypes()) {
            matcher.addPrototype(proto)
        }

        // Test with 3 fresh performances of the novel gesture
        for (i in 1..3) {
            val testNovel = generateSequence(TestGesture.CUSTOM_NOVEL_SIGN, noiseOffset = 0.02f + i * 0.005f)
            val result = matcher.match(testNovel, threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)

            assertEquals(MatchStatus.MATCH, result.status)
            assertEquals("MY_CUSTOM_SIGN", result.recognizedLabel)
        }

        println("=== EXPERIMENT 2: Novel Gesture Learning ===")
        println("Successfully learned novel custom gesture '${novelProfile.label}' with 3 demonstrations!")
    }

    /**
     * EXPERIMENT 3: Unknown Gesture Rejection.
     * Tests that an un-enrolled gesture is rejected as UNKNOWN when distance exceeds threshold.
     */
    @Test
    fun experimentUnknownGestureRejection() {
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        // Only enroll HELP
        store.createProfile("HELP", (1..3).map { generateSequence(TestGesture.HELP_SIGN, noiseOffset = it * 0.01f) })
        for (proto in store.getAllPrototypes()) {
            matcher.addPrototype(proto)
        }

        // Test with un-enrolled NO_SIGN (with conservative threshold)
        val unEnrolledSeq = generateSequence(TestGesture.NO_SIGN, noiseOffset = 0.0f)
        val result = matcher.match(unEnrolledSeq, threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)

        assertEquals(MatchStatus.UNKNOWN, result.status)
        assertEquals("UNKNOWN", result.recognizedLabel)
        println("=== EXPERIMENT 3: Unknown Gesture Rejection ===")
        println("Unenrolled gesture correctly gated as UNKNOWN (Dist: ${result.nearestDistance} > Thresh: ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD})")
    }
}
