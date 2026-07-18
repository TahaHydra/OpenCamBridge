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
            // UUIDv4 has 122 SecureRandom-backed random bits after version/variant bits.
            token = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("accessToken", token).apply()
        }
        StreamState.accessToken.set(token)

        StreamState.streamMode.set(prefs.getString("streamMode", "h264") ?: "h264")
        StreamState.h264Bitrate.set(prefs.getInt("h264Bitrate", 4000000))
        val savedKeyframeInterval = prefs.getInt(
            "h264KeyframeInterval",
            H264SettingsPolicy.KEYFRAME_INTERVAL_SECONDS
        )
        val keyframeInterval = H264SettingsPolicy.normalizeKeyframeInterval(savedKeyframeInterval)
        StreamState.h264KeyframeInterval.set(keyframeInterval)
        if (savedKeyframeInterval != keyframeInterval) {
            prefs.edit().putInt("h264KeyframeInterval", keyframeInterval).apply()
        }

        StreamState.cameraId.set(prefs.getString("cameraId", "0") ?: "0")
        StreamState.width.set(prefs.getInt("width", 1920))
        StreamState.height.set(prefs.getInt("height", 1080))
        StreamState.outputWidth.set(prefs.getInt("outputWidth", 1920))
        StreamState.outputHeight.set(prefs.getInt("outputHeight", 1080))
        StreamState.profile.set(prefs.getString("profile", "adaptive") ?: "adaptive")
        StreamState.fps.set(prefs.getInt("fps", 60))
        StreamState.jpegQuality.set(prefs.getInt("jpegQuality", 85))
        StreamState.previewFitMode.set(prefs.getString("previewFitMode", "fill") ?: "fill")
        // Orientation mode: "auto" (view follows how the phone is held),
        // "16:9" (pinned horizontal), or "9:16" (pinned vertical).
        StreamState.aspectRatio.set(prefs.getString("aspectRatio", "auto") ?: "auto")

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
        StreamState.targetBandwidthMbps.set(prefs.getInt("targetBandwidthMbps", 0))
        StreamState.developerMode.set(prefs.getBoolean("developerMode", false))
        StreamState.refreshConfigSnapshotFromLegacy()
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
            putInt("targetBandwidthMbps", StreamState.targetBandwidthMbps.get())
            putBoolean("developerMode", StreamState.developerMode.get())
            apply()
        }
    }
}
