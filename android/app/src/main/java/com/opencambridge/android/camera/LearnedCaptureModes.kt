package com.opencambridge.android.camera

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Small local evidence cache. No camera access, networking or launch-time probes. */
internal class LearnedCaptureModes(context: Context) {
    private val preferences = context.getSharedPreferences("ocb.capture-evidence.v1", Context.MODE_PRIVATE)
    data class Entry(val best: CaptureVerdict, val last: CaptureVerdict, val at: Long)
    private fun entries(): JSONArray {
        if (preferences.getString("firmware", "") != Build.FINGERPRINT) return JSONArray()
        return try { JSONArray(preferences.getString("entries", "[]")) } catch (_: Exception) { JSONArray() }
    }
    private fun id(key: CaptureTrialKey): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    fun key(cameraId: String, chosen: H264EncoderSelection, preview: Boolean) = CaptureTrialKey(
        Build.FINGERPRINT, cameraId, chosen.mode.width, chosen.mode.height, chosen.mode.fps,
        chosen.codecName, preview, chosen.sessionParameters)
    fun read(key: CaptureTrialKey): Entry? = synchronized(lock) {
        val records = entries()
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (record.optString("key") == id(key)) return@synchronized try {
                Entry(CaptureVerdict.valueOf(record.getString("best")), CaptureVerdict.valueOf(record.getString("last")), record.getLong("at"))
            } catch (_: Exception) { null }
        }
        null
    }
    fun allowed(key: CaptureTrialKey, now: Long = System.currentTimeMillis()): Boolean = read(key)?.let {
        CaptureTrialPolicy.mayAttempt(it.last, it.at, now)
    } ?: true
    fun remember(key: CaptureTrialKey, result: CaptureVerdict) = synchronized(lock) {
        val old = read(key)
        val records = entries()
        val next = JSONArray()
        val identity = id(key)
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (record.optString("key") != identity && index >= records.length() - 63) next.put(record)
        }
        next.put(JSONObject().put("key", identity).put("best", CaptureTrialPolicy.remember(old?.best, result).name)
            .put("last", result.name).put("at", System.currentTimeMillis()))
        preferences.edit().putString("firmware", Build.FINGERPRINT).putString("entries", next.toString()).apply()
    }
    fun clear() = synchronized(lock) { preferences.edit().clear().apply() }
    companion object { private val lock = Any() }
}
