package com.opencambridge.android.service

import com.opencambridge.android.server.UpdateSettingsRequest

/**
 * In-process control bridge between the phone's own Compose UI (StreamViewModel)
 * and the running StreamService.
 *
 * The phone UI used to control itself by POSTing to its own loopback Ktor server
 * (http://127.0.0.1:port). On slower devices (e.g. LineageOS on the OnePlus 9)
 * that intermittently failed with IOException/timeout and every button showed an
 * error. There is no reason for the app to talk to itself over HTTP: the UI now
 * calls these handlers directly, in-process, and they update the same
 * StreamState/SettingsManager, so desktop/web clients still stay in sync via the
 * HTTP API (which remains for remote clients only).
 *
 * Handlers are registered by StreamService while it is alive and cleared on
 * destroy; callers fall back to a local persist when nothing is registered.
 */
object ServiceBridge {
    @Volatile var applyPatch: ((UpdateSettingsRequest, String?) -> Unit)? = null
    @Volatile var setTorch: ((Boolean) -> Unit)? = null
    @Volatile var setLinearZoom: ((Float) -> Unit)? = null
    @Volatile var setZoomRatio: ((Float) -> Unit)? = null
    @Volatile var startCamera: (() -> Unit)? = null
    @Volatile var stopCamera: (() -> Unit)? = null
    @Volatile var recoverCamera: (() -> Unit)? = null
    @Volatile var previewSurfaceChanged: ((Boolean) -> Unit)? = null

    val isServiceRunning: Boolean get() = applyPatch != null

    fun clear() {
        applyPatch = null
        setTorch = null
        setLinearZoom = null
        setZoomRatio = null
        startCamera = null
        stopCamera = null
        recoverCamera = null
        previewSurfaceChanged = null
    }
}
