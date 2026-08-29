package com.signbridge.app.gesture

import android.content.Context
import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Optional debug-only recorder for collecting real MediaPipe sequences for replay/calibration. */
class RealSequenceRecorder(private val context:Context){
    private val dir get()=File(context.filesDir,"gesture_captures").apply{mkdirs()}
    fun save(name:String,sequence:TemporalSequence):File{
        val root=JSONObject().put("name",name).put("frames",JSONArray())
        val arr=root.getJSONArray("frames")
        sequence.frames.forEach{f->arr.put(JSONObject().put("ts",f.timestampMs).put("hand",f.handedness).put("scale",f.handScale).put("wrist",JSONArray().put(f.rawWristPosition.x).put(f.rawWristPosition.y).put(f.rawWristPosition.z)).put("landmarks",JSONArray().also{a->f.landmarks.forEach{p->a.put(p.x).put(p.y).put(p.z)}}))}
        return File(dir,"${name.replace("[^A-Za-z0-9_-]".toRegex(),"_")}_${System.currentTimeMillis()}.json").also{it.writeText(root.toString())}
    }
}
