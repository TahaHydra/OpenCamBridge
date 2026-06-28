package com.opencambridge.android.state

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists application settings to SharedPreferences and applies them to StreamState.
 */
class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("OpenCamBridgeSettings", Context.MODE_PRIVATE)

    fun load() {
        StreamState.accessMode.set(prefs.getString("accessMode", "usbOnly") ?: "usbOnly")
        StreamState.port.set(prefs.getInt("port", 8080))
        
        var token = prefs.getString("accessToken", "") ?: ""
        if (token.isEmpty()) {
            token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString("accessToken", token).apply()
        }
        StreamState.accessToken.set(token)

        StreamState.streamMode.set(prefs.getString("streamMode", "mjpeg") ?: "mjpeg")
        StreamState.h264Bitrate.set(prefs.getInt("h264Bitrate", 4000000))
        StreamState.h264KeyframeInterval.set(prefs.getInt("h264KeyframeInterval", 2))

        StreamState.cameraId.set(prefs.getString("cameraId", "0") ?: "0")
        StreamState.width.set(prefs.getInt("width", 1280))
        StreamState.height.set(prefs.getInt("height", 720))
        StreamState.outputWidth.set(prefs.getInt("outputWidth", 1280))
        StreamState.outputHeight.set(prefs.getInt("outputHeight", 720))
        StreamState.profile.set(prefs.getString("profile", "balanced") ?: "balanced")
        StreamState.fps.set(prefs.getInt("fps", 30))
        StreamState.jpegQuality.set(prefs.getInt("jpegQuality", 85))
        StreamState.previewFitMode.set(prefs.getString("previewFitMode", "fill") ?: "fill")
        val savedAspectRatio = prefs.getString("aspectRatio", "16:9") ?: "16:9"
        StreamState.aspectRatio.set(if (savedAspectRatio == "auto") "16:9" else savedAspectRatio)
        
        StreamState.zoomSpeed.set(prefs.getString("zoomSpeed", "normal") ?: "normal")
        
        try {
            val savedRot = prefs.getString("displayRotation", "0") ?: "0"
            StreamState.displayRotation.set(if (savedRot == "auto") "0" else savedRot)
        } catch (e: ClassCastException) {
            // Safe migration from older Int values
            val oldInt = prefs.getInt("displayRotation", 0)
            StreamState.displayRotation.set(oldInt.toString())
        }
        
        StreamState.mirror.set(prefs.getBoolean("mirror", false))
        StreamState.localPreviewEnabled.set(prefs.getBoolean("localPreviewEnabled", false))
    }

    fun save() {
        prefs.edit().apply {
            putString("accessMode", StreamState.accessMode.get())
            putInt("port", StreamState.port.get())
            putString("accessToken", StreamState.accessToken.get())
            putString("streamMode", StreamState.streamMode.get())
            putInt("h264Bitrate", StreamState.h264Bitrate.get())
            putInt("h264KeyframeInterval", StreamState.h264KeyframeInterval.get())
            putString("cameraId", StreamState.cameraId.get())
            putInt("width", StreamState.width.get())
            putInt("height", StreamState.height.get())
            putInt("outputWidth", StreamState.outputWidth.get())
            putInt("outputHeight", StreamState.outputHeight.get())
            putString("profile", StreamState.profile.get())
            putInt("fps", StreamState.fps.get())
            putInt("jpegQuality", StreamState.jpegQuality.get())
            putString("previewFitMode", StreamState.previewFitMode.get())
            putString("aspectRatio", StreamState.aspectRatio.get())
            putString("zoomSpeed", StreamState.zoomSpeed.get())
            putString("displayRotation", StreamState.displayRotation.get())
            putBoolean("mirror", StreamState.mirror.get())
            putBoolean("localPreviewEnabled", StreamState.localPreviewEnabled.get())
            apply()
        }
    }
}
