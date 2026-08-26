package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic test verifying local JSON serialization, deserialization, and field integrity
 * for [GestureProfile] and [GesturePrototype].
 */
class GestureProfileStorageTest {

    private fun createSyntheticProfile(label: String): GestureProfile {
        val prototypes = (1..3).map { shot ->
            val frames = (0 until 30).map { step ->
                val points = (0 until 21).map { i ->
                    NormalizedLandmarkPoint(i * 0.04f + (shot * 0.01f), i * 0.03f, i * 0.01f)
                }
                NormalizedLandmarkFrame(
                    timestampMs = step * 33L,
                    handedness = "Right",
                    landmarks = points,
                    handScale = 1.0f + (shot * 0.02f),
                    rawWristPosition = LandmarkPoint(0.5f, 0.5f, 0.0f)
                )
            }
            GesturePrototype(
                id = "proto_${label}_$shot",
                displayName = label,
                sequence = TemporalSequence(frames, 30, true)
            )
        }

        return GestureProfile(
            id = "profile_${label.lowercase()}_123",
            label = label,
            prototypes = prototypes
        )
    }

    @Test
    fun testProfileSerializationAndDeserialization() {
        val originalProfiles = listOf(
            createSyntheticProfile("HELP"),
            createSyntheticProfile("EMERGENCY")
        )

        // Serialize
        val serializedJson = GestureProfileStorage.serializeProfiles(originalProfiles)
        assertTrue(serializedJson.isNotEmpty())
        assertTrue(serializedJson.contains("\"label\": \"HELP\""))
        assertTrue(serializedJson.contains("\"label\": \"EMERGENCY\""))

        // Deserialize
        val restoredProfiles = GestureProfileStorage.deserializeProfiles(serializedJson)
        assertEquals(2, restoredProfiles.size)
        assertEquals("HELP", restoredProfiles[0].label)
        assertEquals("EMERGENCY", restoredProfiles[1].label)
        assertEquals(3, restoredProfiles[0].prototypes.size)
        assertEquals(30, restoredProfiles[0].prototypes[0].sequence.frameCount)
        assertEquals(21, restoredProfiles[0].prototypes[0].sequence.frames[0].landmarks.size)
        assertEquals(
            originalProfiles[0].prototypes[0].sequence.frames[0].landmarks[0].x,
            restoredProfiles[0].prototypes[0].sequence.frames[0].landmarks[0].x,
            1e-4f
        )
    }
}
