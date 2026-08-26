package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import java.util.Collections
import java.util.UUID

/**
 * Thread-safe store managing personalized gesture profiles with defensive immutability.
 */
class PersonalGestureStore {

    private val profileList = Collections.synchronizedList(mutableListOf<GestureProfile>())

    val profileCount: Int
        get() = profileList.size

    val totalPrototypeCount: Int
        get() = synchronized(profileList) {
            profileList.sumOf { it.prototypes.size }
        }

    /**
     * Creates and registers a new [GestureProfile] from captured demonstrations.
     * Enforces defensive deep-copying of all landmark frames and coordinates.
     *
     * @param label User-assigned name for the gesture (e.g. "HELP", "YES")
     * @param sequences List of captured temporal sequences (typically 3 demonstrations)
     * @return The created and stored [GestureProfile]
     */
    fun createProfile(label: String, sequences: List<TemporalSequence>): GestureProfile {
        require(label.isNotBlank()) { "Gesture label cannot be empty" }
        require(sequences.isNotEmpty()) { "At least one sequence is required to create a profile" }

        val trimmedLabel = label.trim().uppercase()
        val profileId = "profile_${trimmedLabel.lowercase().replace("\\s+".toRegex(), "_")}_${UUID.randomUUID().toString().take(6)}"

        val prototypes = sequences.mapIndexed { index, seq ->
            val defensiveFrames = seq.frames.map { f ->
                val defensivePoints = f.landmarks.map { p -> NormalizedLandmarkPoint(p.x, p.y, p.z) }
                NormalizedLandmarkFrame(
                    timestampMs = f.timestampMs,
                    handedness = f.handedness,
                    landmarks = defensivePoints,
                    handScale = f.handScale,
                    rawWristPosition = LandmarkPoint(f.rawWristPosition.x, f.rawWristPosition.y, f.rawWristPosition.z)
                )
            }
            GesturePrototype(
                id = "${profileId}_shot_${index + 1}",
                displayName = trimmedLabel,
                sequence = TemporalSequence(defensiveFrames, seq.windowSize, isReady = defensiveFrames.size == seq.windowSize)
            )
        }

        val profile = GestureProfile(
            id = profileId,
            label = trimmedLabel,
            prototypes = prototypes
        )

        addProfile(profile)
        return profile
    }

    /**
     * Stores a [GestureProfile] in memory.
     */
    fun addProfile(profile: GestureProfile) {
        synchronized(profileList) {
            profileList.removeAll { it.id == profile.id || it.label.equals(profile.label, ignoreCase = true) }
            profileList.add(profile)
        }
    }

    /**
     * Retrieves a profile by its ID.
     */
    fun getProfile(id: String): GestureProfile? {
        return synchronized(profileList) {
            profileList.find { it.id == id }
        }
    }

    /**
     * Returns an immutable copy of all enrolled gesture profiles.
     */
    fun getAllProfiles(): List<GestureProfile> {
        return synchronized(profileList) {
            ArrayList(profileList)
        }
    }

    /**
     * Removes a profile by ID.
     */
    fun removeProfile(id: String): Boolean {
        return synchronized(profileList) {
            profileList.removeAll { it.id == id }
        }
    }

    /**
     * Clears all enrolled profiles from memory.
     */
    fun clearAll() {
        synchronized(profileList) {
            profileList.clear()
        }
    }

    /**
     * Flattens and returns all prototypes across all enrolled profiles for DTW matching.
     */
    fun getAllPrototypes(): List<GesturePrototype> {
        return synchronized(profileList) {
            profileList.flatMap { it.prototypes }
        }
    }
}
