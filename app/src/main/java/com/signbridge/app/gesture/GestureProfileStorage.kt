package com.signbridge.app.gesture

import android.content.Context
import android.util.Log
import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.LandmarkPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Versioned, atomic local-only JSON persistence for personalized gesture profiles. */
object GestureProfileStorage {
    private const val TAG = "GestureProfileStorage"
    private const val FILE = "enrolled_gesture_profiles.json"
    private const val VERSION = 2

    fun serializeProfiles(profiles: List<GestureProfile>): String {
        val root = JSONObject().put("version", VERSION).put("profiles", JSONArray())
        val arr = root.getJSONArray("profiles")
        profiles.forEach { p ->
            val po = JSONObject().put("id", p.id).put("label", p.label).put("createdAtMs", p.createdAtMs).put("prototypes", JSONArray())
            val pa = po.getJSONArray("prototypes")
            p.prototypes.forEach { proto ->
                val pr = JSONObject().put("id", proto.id).put("displayName", proto.displayName).put("createdAtMs", proto.createdAtMs).put("windowSize", proto.sequence.windowSize).put("sequenceId", proto.sequence.sequenceId).put("frames", JSONArray())
                val fa = pr.getJSONArray("frames")
                proto.sequence.frames.forEach { f ->
                    val fo = JSONObject().put("ts", f.timestampMs).put("hand", f.handedness).put("scale", f.handScale).put("wrist", JSONArray().put(f.rawWristPosition.x).put(f.rawWristPosition.y).put(f.rawWristPosition.z)).put("landmarks", JSONArray())
                    val la = fo.getJSONArray("landmarks")
                    f.landmarks.forEach { pt -> la.put(pt.x).put(pt.y).put(pt.z) }
                    fa.put(fo)
                }
                pa.put(pr)
            }
            arr.put(po)
        }
        return root.toString(2)
    }

    fun deserializeProfiles(json: String): List<GestureProfile> {
        val trimmed = json.trim()
        val arr: JSONArray = if (trimmed.startsWith("[")) {
            try { JSONArray(trimmed) } catch (e: Exception) { Log.e(TAG, "Invalid profile JSONArray", e); return emptyList() }
        } else {
            val root = try { JSONObject(trimmed) } catch (e: Exception) { Log.e(TAG, "Invalid profile JSONObject", e); return emptyList() }
            root.optJSONArray("profiles") ?: return emptyList()
        }

        val out = mutableListOf<GestureProfile>()
        for (i in 0 until arr.length()) {
            val po = arr.optJSONObject(i) ?: continue
            val id = po.optString("id", "")
            val label = po.optString("label", "")
            if (id.isBlank() || label.isBlank()) continue

            val pa = po.optJSONArray("prototypes") ?: continue
            val protos = mutableListOf<GesturePrototype>()
            for (j in 0 until pa.length()) {
                val pr = pa.optJSONObject(j) ?: continue
                val fa = pr.optJSONArray("frames") ?: continue
                val frames = mutableListOf<NormalizedLandmarkFrame>()
                for (k in 0 until fa.length()) {
                    val fo = fa.optJSONObject(k) ?: continue
                    val la = fo.optJSONArray("landmarks") ?: continue
                    if (la.length() != 63) continue
                    val pts = mutableListOf<NormalizedLandmarkPoint>()
                    for (n in 0 until 63 step 3) {
                        pts.add(NormalizedLandmarkPoint(la.optDouble(n, 0.0).toFloat(), la.optDouble(n + 1, 0.0).toFloat(), la.optDouble(n + 2, 0.0).toFloat()))
                    }
                    val w = fo.optJSONArray("wrist")
                    val wrist = if (w != null && w.length() >= 3) {
                        LandmarkPoint(w.optDouble(0, 0.5).toFloat(), w.optDouble(1, 0.5).toFloat(), w.optDouble(2, 0.0).toFloat())
                    } else {
                        LandmarkPoint(0.5f, 0.5f, 0f)
                    }
                    frames.add(NormalizedLandmarkFrame(fo.optLong("ts", 0L), fo.optString("hand", "Unknown"), pts, fo.optDouble("scale", 1.0).toFloat(), wrist))
                }
                if (frames.isNotEmpty()) {
                    protos.add(GesturePrototype(pr.optString("id", "${id}_shot_$j"), pr.optString("displayName", label), TemporalSequence(frames, pr.optInt("windowSize", frames.size), true, pr.optLong("sequenceId", 0L)), pr.optLong("createdAtMs", System.currentTimeMillis())))
                }
            }
            if (protos.isNotEmpty()) {
                out.add(GestureProfile(id, label, protos, po.optLong("createdAtMs", System.currentTimeMillis())))
            }
        }
        return out
    }

    fun saveProfiles(context: Context, profiles: List<GestureProfile>): Boolean = try {
        val target = File(context.filesDir, FILE)
        val temp = File(context.filesDir, "$FILE.tmp")
        temp.writeText(serializeProfiles(profiles))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
        Log.i(TAG, "Saved ${profiles.size} profiles (${target.length()} bytes)")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed saving profiles", e)
        false
    }

    fun loadProfiles(context: Context): List<GestureProfile> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            deserializeProfiles(f.readText()).also { Log.i(TAG, "Loaded ${it.size} profiles") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading profiles", e)
            emptyList()
        }
    }

    fun clearProfiles(context: Context): Boolean = try {
        File(context.filesDir, FILE).delete()
        true
    } catch (_: Exception) {
        false
    }
}
