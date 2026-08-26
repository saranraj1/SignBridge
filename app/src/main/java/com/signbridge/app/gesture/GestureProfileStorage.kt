package com.signbridge.app.gesture

import android.content.Context
import android.util.Log
import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import java.io.File

/**
 * Pure Kotlin local persistence manager for [GestureProfile] instances.
 *
 * Saves enrolled gesture profiles to internal disk storage.
 * Operates 100% locally and offline without cloud dependencies, database overhead, or platform JSON stubs.
 */
object GestureProfileStorage {

    private const val TAG = "GestureProfileStorage"
    private const val PROFILES_FILE_NAME = "enrolled_gesture_profiles.json"

    /**
     * Serializes a list of [GestureProfile] into a structured JSON string.
     */
    fun serializeProfiles(profiles: List<GestureProfile>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        profiles.forEachIndexed { pIdx, profile ->
            sb.append("  {\n")
            sb.append("    \"id\": \"${escapeJson(profile.id)}\",\n")
            sb.append("    \"label\": \"${escapeJson(profile.label)}\",\n")
            sb.append("    \"createdAtMs\": ${profile.createdAtMs},\n")
            sb.append("    \"prototypes\": [\n")
            profile.prototypes.forEachIndexed { prIdx, proto ->
                sb.append("      {\n")
                sb.append("        \"id\": \"${escapeJson(proto.id)}\",\n")
                sb.append("        \"displayName\": \"${escapeJson(proto.displayName)}\",\n")
                sb.append("        \"createdAtMs\": ${proto.createdAtMs},\n")
                sb.append("        \"windowSize\": ${proto.sequence.windowSize},\n")
                sb.append("        \"frames\": [\n")
                proto.sequence.frames.forEachIndexed { fIdx, frame ->
                    sb.append("          {\n")
                    sb.append("            \"ts\": ${frame.timestampMs},\n")
                    sb.append("            \"hand\": \"${escapeJson(frame.handedness)}\",\n")
                    sb.append("            \"scale\": ${frame.handScale},\n")
                    sb.append("            \"wrist\": [${frame.rawWristPosition.x}, ${frame.rawWristPosition.y}, ${frame.rawWristPosition.z}],\n")
                    sb.append("            \"landmarks\": [")
                    frame.landmarks.forEachIndexed { lIdx, pt ->
                        sb.append("${pt.x},${pt.y},${pt.z}")
                        if (lIdx < frame.landmarks.size - 1) sb.append(",")
                    }
                    sb.append("]\n")
                    sb.append("          }")
                    if (fIdx < proto.sequence.frames.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("        ]\n")
                sb.append("      }")
                if (prIdx < profile.prototypes.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("    ]\n")
            sb.append("  }")
            if (pIdx < profiles.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * Deserializes a JSON string into a list of [GestureProfile] instances.
     */
    fun deserializeProfiles(json: String): List<GestureProfile> {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()

        val profiles = mutableListOf<GestureProfile>()
        try {
            // Tokenize by profile blocks
            val profileBlocks = extractBlocks(trimmed, '[', ']')
            for (pBlock in profileBlocks) {
                val pId = extractString(pBlock, "id") ?: continue
                val pLabel = extractString(pBlock, "label") ?: pId
                val pCreatedAt = extractLong(pBlock, "createdAtMs") ?: System.currentTimeMillis()

                val protoSectionStart = pBlock.indexOf("\"prototypes\":")
                if (protoSectionStart == -1) continue

                val protosSub = pBlock.substring(protoSectionStart)
                val protoBlocks = extractBlocks(protosSub, '[', ']')

                val prototypes = mutableListOf<GesturePrototype>()
                for (prBlock in protoBlocks) {
                    val prId = extractString(prBlock, "id") ?: continue
                    val prName = extractString(prBlock, "displayName") ?: pLabel
                    val prCreatedAt = extractLong(prBlock, "createdAtMs") ?: pCreatedAt
                    val windowSize = extractInt(prBlock, "windowSize") ?: 30

                    val framesSectionStart = prBlock.indexOf("\"frames\":")
                    if (framesSectionStart == -1) continue

                    val framesSub = prBlock.substring(framesSectionStart)
                    val frameBlocks = extractBlocks(framesSub, '[', ']')

                    val frames = mutableListOf<NormalizedLandmarkFrame>()
                    for (fBlock in frameBlocks) {
                        val ts = extractLong(fBlock, "ts") ?: 0L
                        val hand = extractString(fBlock, "hand") ?: "Right"
                        val scale = extractFloat(fBlock, "scale") ?: 1.0f

                        val wristValues = extractFloatList(fBlock, "wrist")
                        val wrist = if (wristValues.size >= 3) {
                            LandmarkPoint(wristValues[0], wristValues[1], wristValues[2])
                        } else {
                            LandmarkPoint(0.5f, 0.5f, 0.0f)
                        }

                        val landmarkFloats = extractFloatList(fBlock, "landmarks")
                        val landmarks = mutableListOf<NormalizedLandmarkPoint>()
                        for (idx in landmarkFloats.indices step 3) {
                            if (idx + 2 < landmarkFloats.size) {
                                landmarks.add(
                                    NormalizedLandmarkPoint(
                                        landmarkFloats[idx],
                                        landmarkFloats[idx + 1],
                                        landmarkFloats[idx + 2]
                                    )
                                )
                            }
                        }

                        if (landmarks.size == 21) {
                            frames.add(
                                NormalizedLandmarkFrame(
                                    timestampMs = ts,
                                    handedness = hand,
                                    landmarks = landmarks,
                                    handScale = scale,
                                    rawWristPosition = wrist
                                )
                            )
                        }
                    }

                    if (frames.isNotEmpty()) {
                        prototypes.add(
                            GesturePrototype(
                                id = prId,
                                displayName = prName,
                                sequence = TemporalSequence(frames, windowSize, isReady = frames.size == windowSize),
                                createdAtMs = prCreatedAt
                            )
                        )
                    }
                }

                if (prototypes.isNotEmpty()) {
                    profiles.add(
                        GestureProfile(
                            id = pId,
                            label = pLabel,
                            prototypes = prototypes,
                            createdAtMs = pCreatedAt
                        )
                    )
                }
            }
        } catch (e: Exception) {
            println("Error parsing gesture profiles JSON: ${e.message}")
        }
        return profiles
    }

    /**
     * Persists all enrolled gesture profiles to internal disk storage.
     */
    fun saveProfiles(context: Context, profiles: List<GestureProfile>): Boolean {
        return try {
            val json = serializeProfiles(profiles)
            val file = File(context.filesDir, PROFILES_FILE_NAME)
            file.writeText(json)
            Log.i(TAG, "Saved ${profiles.size} profiles to local storage (${file.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving profiles: ${e.message}", e)
            false
        }
    }

    /**
     * Loads all persisted gesture profiles from internal disk storage.
     */
    fun loadProfiles(context: Context): List<GestureProfile> {
        val file = File(context.filesDir, PROFILES_FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            val json = file.readText()
            val loaded = deserializeProfiles(json)
            Log.i(TAG, "Loaded ${loaded.size} profiles from local storage")
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading profiles: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Clears persisted gesture profiles from disk.
     */
    fun clearProfiles(context: Context): Boolean {
        val file = File(context.filesDir, PROFILES_FILE_NAME)
        return if (file.exists()) file.delete() else true
    }

    // --- JSON Parsing Helpers ---

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractLong(json: String, key: String): Long? {
        val pattern = "\"$key\"\\s*:\\s*([0-9]+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractInt(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*([0-9]+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractFloat(json: String, key: String): Float? {
        val pattern = "\"$key\"\\s*:\\s*([-0-9.]+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractFloatList(json: String, key: String): List<Float> {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(json) ?: return emptyList()
        val content = match.groupValues[1]
        if (content.isBlank()) return emptyList()
        return content.split(",").mapNotNull { it.trim().toFloatOrNull() }
    }

    private fun extractBlocks(json: String, openBracket: Char, closeBracket: Char): List<String> {
        val blocks = mutableListOf<String>()
        val startIdx = json.indexOf(openBracket)
        if (startIdx == -1) return emptyList()

        var depth = 0
        var blockStart = -1

        for (i in startIdx until json.length) {
            val c = json[i]
            if (c == '{') {
                if (depth == 1 && blockStart == -1) {
                    blockStart = i
                }
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 1 && blockStart != -1) {
                    blocks.add(json.substring(blockStart, i + 1))
                    blockStart = -1
                }
            } else if (c == openBracket) {
                if (depth == 0) depth = 1
            } else if (c == closeBracket && depth <= 1) {
                break
            }
        }
        return blocks
    }
}
