package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Diagnostic unit test suite investigating recognition stability, prototype immutability,
 * distance distributions, ambiguity margin gating, and sequence statistics.
 */
class RecognitionStabilityTest {

    private fun generateSyntheticSequence(
        direction: Float,
        variation: Float = 0f,
        isStatic: Boolean = false
    ): TemporalSequence {
        val frames = (0 until 30).map { step ->
            val progress = if (isStatic) 0f else step.toFloat() / 29f
            val points = (0 until 21).map { i ->
                NormalizedLandmarkPoint(
                    x = (i * 0.04f) + (progress * direction) + variation,
                    y = (i * 0.03f) - (progress * direction * 0.5f) + variation,
                    z = (i * 0.01f) + (sin(progress * 3.14f).toFloat() * 0.02f)
                )
            }
            NormalizedLandmarkFrame(
                timestampMs = step * 33L,
                handedness = "Right",
                landmarks = points,
                handScale = 1.0f + variation,
                rawWristPosition = LandmarkPoint(0.5f + (progress * 0.1f), 0.5f, 0f)
            )
        }
        return TemporalSequence(frames, 30, true)
    }

    @Test
    fun testPrototypeImmutabilityAcrossBufferMutations() {
        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()

        val p1Seq = generateSyntheticSequence(0.3f, 0.0f)
        val p2Seq = generateSyntheticSequence(0.3f, 0.01f)
        val p3Seq = generateSyntheticSequence(0.3f, 0.02f)

        val profile = store.createProfile("TEST_GESTURE", listOf(p1Seq, p2Seq, p3Seq))
        profile.prototypes.forEach { matcher.addPrototype(it) }

        // Snapshot initial prototype coordinate sum
        val initialSumP1 = profile.prototypes[0].sequence.frames.flatMap { it.landmarks }.sumOf { it.x.toDouble() }

        // Simulate 150 live rolling buffer frame additions
        val buffer = TemporalBuffer(30)
        for (i in 0 until 150) {
            val liveFrame = NormalizedLandmarkFrame(
                timestampMs = 1000L + (i * 33L),
                handedness = "Right",
                landmarks = (0 until 21).map { NormalizedLandmarkPoint(it * 0.99f, it * 0.88f, 0.5f) },
                handScale = 2.5f,
                rawWristPosition = LandmarkPoint(0.9f, 0.9f, 0f)
            )
            buffer.addFrame(liveFrame)
            matcher.match(buffer.getSnapshot(), threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        }

        // Verify stored prototype remained 100% immutable
        val currentSumP1 = profile.prototypes[0].sequence.frames.flatMap { it.landmarks }.sumOf { it.x.toDouble() }
        assertEquals(
            "Enrolled prototype data must remain strictly immutable across live buffer operations",
            initialSumP1,
            currentSumP1,
            1e-6
        )
    }

    @Test
    fun testCalibratedDistanceDistributions() {
        val helpDemos = listOf(
            generateSyntheticSequence(0.35f, 0.0f),
            generateSyntheticSequence(0.35f, 0.01f),
            generateSyntheticSequence(0.35f, 0.02f)
        )
        val yesDemos = listOf(
            generateSyntheticSequence(-0.35f, 0.0f),
            generateSyntheticSequence(-0.35f, 0.01f),
            generateSyntheticSequence(-0.35f, 0.02f)
        )

        val store = PersonalGestureStore()
        val matcher = PrototypeMatcher()
        store.createProfile("HELP", helpDemos).prototypes.forEach { matcher.addPrototype(it) }
        store.createProfile("YES", yesDemos).prototypes.forEach { matcher.addPrototype(it) }

        // 1. Same-gesture query (HELP with slight natural jitter)
        val sameGestureQuery = generateSyntheticSequence(0.35f, 0.015f)
        val sameResult = matcher.match(sameGestureQuery, threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        assertTrue("Same gesture must pass threshold", sameResult.nearestDistance <= GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        assertEquals("Same gesture must be classified as MATCH", MatchStatus.MATCH, sameResult.status)
        assertEquals("HELP", sameResult.recognizedLabel)
        assertTrue("Intra-gesture distance should be small (<0.20)", sameResult.nearestDistance < 0.20)

        // 2. Different gesture query (YES)
        val yesQuery = generateSyntheticSequence(-0.35f, 0.012f)
        val yesResult = matcher.match(yesQuery, threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        assertEquals(MatchStatus.MATCH, yesResult.status)
        assertEquals("YES", yesResult.recognizedLabel)

        // 3. Static resting hand (no dynamic movement)
        val staticHandQuery = generateSyntheticSequence(0.0f, 0.0f, isStatic = true)
        val staticResult = matcher.match(staticHandQuery, threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        assertTrue(
            "Static resting hand distance (${staticResult.nearestDistance}) or motion check must cause UNKNOWN",
            staticResult.nearestDistance > GestureConfig.DEFAULT_RECOGNITION_THRESHOLD || staticResult.status == MatchStatus.UNKNOWN
        )
        assertEquals("Static hand must be rejected as UNKNOWN", MatchStatus.UNKNOWN, staticResult.status)
    }

    @Test
    fun testAmbiguityMarginGating() {
        val matcher = PrototypeMatcher()

        // Create two closely aligned but distinct prototypes
        val protoA = GesturePrototype("A_1", "GESTURE_A", generateSyntheticSequence(0.20f, 0.0f))
        val protoB = GesturePrototype("B_1", "GESTURE_B", generateSyntheticSequence(0.22f, 0.0f))
        matcher.addPrototype(protoA)
        matcher.addPrototype(protoB)

        // Query placed midway between A and B (ambiguous)
        val ambiguousQuery = generateSyntheticSequence(0.21f, 0.0f)
        val ambigResult = matcher.match(
            ambiguousQuery,
            threshold = 0.50,
            ambiguityMargin = 0.06
        )

        // The nearest distance is small (<0.50), but margin is tiny (<0.06), so it must trigger AMBIGUOUS
        assertTrue("Nearest distance is small", ambigResult.nearestDistance < 0.50)
        assertTrue("Margin is below ambiguity threshold", ambigResult.margin < 0.06)
        assertEquals("Ambiguous candidate must be rejected as AMBIGUOUS", MatchStatus.AMBIGUOUS, ambigResult.status)
        assertFalse(ambigResult.isAccepted)
    }

    @Test
    fun testSequenceStatisticsComputation() {
        val seq = generateSyntheticSequence(0.4f, 0.0f)
        val stats = seq.computeStatistics()

        assertEquals(30, stats.frameCount)
        assertTrue(stats.durationMs > 0)
        assertEquals("Right", stats.dominantHandedness)
        assertTrue("All landmarks should be valid", stats.allValidLandmarks)
        assertTrue("Dynamic gesture should exhibit positive motion variance", stats.motionVariance > 0.005f)

        val summary = stats.formatSummary()
        assertNotNull(summary)
        assertTrue(summary.contains("frames=30"))
        assertTrue(summary.contains("hand=Right"))
    }
}
