package com.opencambridge.android.state

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists application settings to SharedPreferences and applies them to StreamState.
 */
class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("OpenCamBridgeSettings", Context.MODE_PRIVATE)

    fun load() {
        StreamState.cameraId.set(prefs.getString("cameraId", "0") ?: "0")
        StreamState.width.set(prefs.getInt("width", 1280))
        StreamState.height.set(prefs.getInt("height", 720))
        StreamState.fps.set(prefs.getInt("fps", 30))
        StreamState.jpegQuality.set(prefs.getInt("jpegQuality", 85))
        StreamState.previewFitMode.set(prefs.getString("previewFitMode", "fit") ?: "fit")
        StreamState.localPreviewEnabled.set(prefs.getBoolean("localPreviewEnabled", false))
    }

    fun save() {
        prefs.edit().apply {
            putString("cameraId", StreamState.cameraId.get())
            putInt("width", StreamState.width.get())
            putInt("height", StreamState.height.get())
            putInt("fps", StreamState.fps.get())
            putInt("jpegQuality", StreamState.jpegQuality.get())
            putString("previewFitMode", StreamState.previewFitMode.get())
            putBoolean("localPreviewEnabled", StreamState.localPreviewEnabled.get())
            apply()
        }
    }
}
