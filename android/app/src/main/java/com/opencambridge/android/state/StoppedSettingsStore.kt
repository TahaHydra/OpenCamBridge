package com.opencambridge.android.state

import android.content.Context
import androidx.core.content.edit

/**
 * Access to the persisted settings file while StreamService is NOT running.
 *
 * StreamService loads settings into StreamState only when it starts, so while
 * OpenCamBridge is stopped the in-memory state holds defaults, not what the
 * user chose. The stopped screen and Settings read the real values from here.
 *
 * Writes are deliberately limited to what is safe to change without the
 * pipeline's validation: how the next start binds (USB only / Wi-Fi + token,
 * port, token) and whether the phone preview is requested. Camera, format and
 * size changes still go exclusively through the running pipeline controller,
 * which validates them against the lens's real capabilities. The next
 * SettingsManager.load() at Start picks these values up.
 *
 * Keys and defaults mirror SettingsManager, which owns the file's schema.
 */
object StoppedSettingsStore {
    private const val PREFS = "OpenCamBridgeSettings"

    data class Snapshot(
        val accessMode: String,
        val port: Int,
        val accessToken: String,
        val localPreviewEnabled: Boolean,
        val cameraId: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val streamMode: String,
        val mirror: Boolean,
        val displayRotation: String,
    )

    fun read(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rotation = try {
            prefs.getString("displayRotation", "0") ?: "0"
        } catch (_: ClassCastException) {
            prefs.getInt("displayRotation", 0).toString()
        }
        return Snapshot(
            accessMode = prefs.getString("accessMode", "usbOnly") ?: "usbOnly",
            port = prefs.getInt("port", 8080),
            accessToken = prefs.getString("accessToken", "") ?: "",
            localPreviewEnabled = prefs.getBoolean("localPreviewEnabled", false),
            cameraId = prefs.getString("cameraId", "0") ?: "0",
            width = prefs.getInt("width", 1920),
            height = prefs.getInt("height", 1080),
            fps = prefs.getInt("fps", 60),
            streamMode = prefs.getString("streamMode", "h264") ?: "h264",
            mirror = prefs.getBoolean("mirror", false),
            displayRotation = if (rotation == "auto") "0" else rotation,
        )
    }

    fun writeConnection(context: Context, accessMode: String? = null, port: Int? = null, accessToken: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            accessMode?.let { putString("accessMode", it) }
            port?.let { putInt("port", it) }
            accessToken?.let { putString("accessToken", it) }
        }
    }

    fun writePhonePreview(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("localPreviewEnabled", enabled)
        }
    }
}
