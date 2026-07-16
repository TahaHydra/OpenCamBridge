package com.opencambridge.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.opencambridge.android.MainActivity
import com.opencambridge.android.R
import com.opencambridge.android.camera.H264Streamer
import com.opencambridge.android.camera.MjpegStreamer
import com.opencambridge.android.server.ControlServer
import com.opencambridge.android.server.UpdateSettingsRequest
import com.opencambridge.android.state.AppLogger
import com.opencambridge.android.state.LifecycleState
import com.opencambridge.android.state.SettingsManager
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "StreamService"
private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "opencambridge_stream"

class StreamService : LifecycleService() {

    private lateinit var controlServer: ControlServer
    private lateinit var mjpegStreamer: MjpegStreamer
    private lateinit var h264Streamer: H264Streamer
    private lateinit var settingsManager: SettingsManager
    private var orientationListener: android.view.OrientationEventListener? = null
    private var monitorJob: Job? = null
    private var lowH264Windows = 0

    private val cameraMutex = Mutex()

    companion object {
        const val ACTION_STOP = "com.opencambridge.android.ACTION_STOP"

        fun startIntent(context: Context) = Intent(context, StreamService::class.java)

        fun stopIntent(context: Context) = Intent(context, StreamService::class.java).apply {
            action = ACTION_STOP
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        settingsManager = SettingsManager(applicationContext)
        settingsManager.load()

        mjpegStreamer = MjpegStreamer(context = applicationContext, lifecycleOwner = this)
        h264Streamer = H264Streamer(context = applicationContext, lifecycleOwner = this)

        controlServer = ControlServer(
            context = applicationContext,
            settingsManager = settingsManager,
            h264Streamer = h264Streamer,
            onStartCamera = { startCamera() },
            onStopCamera = { stopCamera() },
            onApplySettingsPatch = { req, source -> applySettingsPatch(req, source) },
            onSetZoomRatio = { ratio ->
                if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setZoomRatio(ratio) else mjpegStreamer.setZoomRatio(ratio)
            },
            onSetLinearZoom = { linear ->
                if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setLinearZoom(linear) else mjpegStreamer.setLinearZoom(linear)
            },
            onSetTorch = { enabled ->
                if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setTorch(enabled) else mjpegStreamer.setTorch(enabled)
            },
            onRecoverCamera = { recoverCamera() }
        )

        // Expose the same control handlers in-process so the phone UI can call
        // them directly instead of POSTing to its own loopback server.
        ServiceBridge.applyPatch = { req, source -> applySettingsPatch(req, source) }
        ServiceBridge.setTorch = { enabled ->
            if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setTorch(enabled) else mjpegStreamer.setTorch(enabled)
        }
        ServiceBridge.setLinearZoom = { linear ->
            if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setLinearZoom(linear) else mjpegStreamer.setLinearZoom(linear)
        }
        ServiceBridge.setZoomRatio = { ratio ->
            if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setZoomRatio(ratio) else mjpegStreamer.setZoomRatio(ratio)
        }
        ServiceBridge.startCamera = { startCamera() }
        ServiceBridge.stopCamera = { stopCamera() }
        ServiceBridge.recoverCamera = { recoverCamera() }

        // Track the PHYSICAL device orientation (accelerometer, works with the
        // app in background and with display auto-rotate locked) and feed it to
        // CameraX as targetRotation. imageInfo.rotationDegrees then reports the
        // exact rotation that makes the frame upright for how the phone is held
        // right now — vertical, horizontal, or upside down — which is what the
        // MJPEG streamer bakes into the actual streamed pixels. Without this the
        // stream is only upright for one specific phone orientation.
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return // e.g. flat on a desk: keep last known
                val surfaceRotation = when (orientation) {
                    in 45..134 -> android.view.Surface.ROTATION_270
                    in 135..224 -> android.view.Surface.ROTATION_180
                    in 225..314 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }
                if (StreamState.deviceSurfaceRotation.getAndSet(surfaceRotation) != surfaceRotation) {
                    StreamState.imageAnalysisUseCase?.targetRotation = surfaceRotation
                    AppLogger.i("Rotation", "Device orientation changed -> targetRotation=$surfaceRotation")
                }
            }
        }
        if (orientationListener?.canDetectOrientation() == true) {
            orientationListener?.enable()
        } else {
            AppLogger.w("Rotation", "Device cannot detect orientation; stream stays upright only for the natural (vertical) position")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        val ip = NetworkUtils.getWifiIpAddress(applicationContext) ?: "device-ip"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(ip), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(ip))
        }

        controlServer.start()
        val port = StreamState.port.get()
        Log.d(TAG, "StreamService started. Server on port $port.")
        AppLogger.i("System", "StreamService started on port $port")

        // Auto-start stream
        startCamera()

        // Start bandwidth monitoring
        if (monitorJob?.isActive != true) monitorJob = lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(2000)

                if (StreamState.h264Failed.getAndSet(false) && StreamState.activeStreamMode.get() == "h264") {
                    cameraMutex.withLock {
                        AppLogger.w("H264", "Encoder failed at runtime; switching to MJPEG compatibility mode")
                        StreamState.lifecycleState.set(LifecycleState.REBINDING)
                        h264Streamer.stop()
                        kotlinx.coroutines.delay(150)
                        mjpegStreamer.start()
                        StreamState.activeStreamMode.set("mjpeg")
                        StreamState.fallbackUsed.set(true)
                        StreamState.lifecycleState.set(LifecycleState.STREAMING)
                        StreamState.streaming.set(true)
                    }
                }

                if (StreamState.activeStreamMode.get() == "h264") {
                    val target = StreamState.selectedFps.get()
                    val actual = minOf(StreamState.captureFps.get(), StreamState.encodedFps.get())
                    lowH264Windows = if (target > 0 && actual > 0 && actual * 100 < target * 80) lowH264Windows + 1 else 0
                    // Ignore transient camera/codec warm-up jitter. A profile
                    // is downgraded only after four consecutive two-second
                    // windows below 80% of its actual selected target.
                    if (lowH264Windows >= 4) {
                        lowH264Windows = 0
                        val next = h264Streamer.prepareAdaptiveDowngrade()
                        if (next != null) {
                            cameraMutex.withLock {
                                val reason = "${StreamState.encodedWidth.get()}x${StreamState.encodedHeight.get()}@${target} could not sustain $target FPS; adapting to ${next.width}x${next.height}@${next.fps}"
                                AppLogger.w("H264", reason)
                                StreamState.fallbackReason.set(reason)
                                StreamState.fallbackUsed.set(true)
                                StreamState.lifecycleState.set(LifecycleState.REBINDING)
                                h264Streamer.stop()
                                kotlinx.coroutines.delay(150)
                                h264Streamer.start()
                                StreamState.lifecycleState.set(LifecycleState.STREAMING)
                                StreamState.streaming.set(true)
                            }
                        } else {
                            StreamState.fallbackReason.set("Lowest H.264 profile could not sustain its target FPS")
                            StreamState.h264Failed.set(true)
                        }
                    }
                } else {
                    lowH264Windows = 0
                }

                val sent = StreamState.bytesSentThisSecond.getAndSet(0L)
                val bps = sent / 2.0 // average over 2 seconds
                val mbps = (bps * 8.0) / 1_000_000.0
                StreamState.estimatedMbps.set(String.format(java.util.Locale.US, "%.2f", mbps))

                val targetBandwidth = StreamState.targetBandwidthMbps.get()
                if (targetBandwidth > 0 && StreamState.streamMode.get() == "mjpeg" && StreamState.lifecycleState.get() == LifecycleState.STREAMING) {
                    val currentQ = StreamState.jpegQuality.get()
                    if (mbps > targetBandwidth * 1.15 && currentQ > 40) {
                        StreamState.jpegQuality.set((currentQ - 3).coerceAtLeast(40))
                        StreamState.incrementRevision("auto-bandwidth")
                    } else if (mbps < targetBandwidth * 0.75 && currentQ < 95) {
                        StreamState.jpegQuality.set((currentQ + 2).coerceAtMost(95))
                        StreamState.incrementRevision("auto-bandwidth")
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "StreamService destroying")
        AppLogger.i("System", "StreamService stopping completely")
        ServiceBridge.clear()
        orientationListener?.disable()
        stopCamera()
        controlServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // --- State Machine & Controller Methods ---

    private fun startCamera() {
        lifecycleScope.launch {
            cameraMutex.withLock {
                val currentState = StreamState.lifecycleState.get()
                if (currentState == LifecycleState.STARTING || currentState == LifecycleState.STREAMING || currentState == LifecycleState.REBINDING) {
                    return@withLock
                }

                StreamState.lifecycleState.set(LifecycleState.STARTING)
                StreamState.lastError.set("")
                StreamState.streaming.set(true)
                Log.d(TAG, "Camera STARTING")
                AppLogger.i("Camera", "Camera starting")

                try {
                    startSelectedPipeline()
                    StreamState.lifecycleState.set(LifecycleState.STREAMING)
                    Log.d(TAG, "Camera STREAMING")
                    AppLogger.i("Camera", "Camera streaming successfully")
                } catch (e: Exception) {
                    handleCameraError("Failed to start camera", e)
                }
            }
        }
    }

    private fun stopCamera() {
        lifecycleScope.launch {
            cameraMutex.withLock {
                StreamState.lifecycleState.set(LifecycleState.STOPPING)
                StreamState.streaming.set(false)
                Log.d(TAG, "Camera STOPPING")
                AppLogger.i("Camera", "Camera stopping")

                try {
                    mjpegStreamer.stop()
                    h264Streamer.stop()
                    StreamState.lifecycleState.set(LifecycleState.STOPPED)
                    Log.d(TAG, "Camera STOPPED")
                    AppLogger.i("Camera", "Camera stopped cleanly")
                } catch (e: Exception) {
                    handleCameraError("Failed to stop camera cleanly", e)
                }
            }
        }
    }

    private fun rebindCamera() {
        lifecycleScope.launch {
            cameraMutex.withLock {
                val currentState = StreamState.lifecycleState.get()
                if (currentState != LifecycleState.STREAMING && currentState != LifecycleState.ERROR) {
                    return@withLock
                }

                Log.d(TAG, "Camera REBINDING")
                AppLogger.i("Camera", "Camera rebinding due to setting change")
                StreamState.lifecycleState.set(LifecycleState.REBINDING)
                StreamState.streaming.set(false)

                try {
                    mjpegStreamer.stop()
                    h264Streamer.stop()
                    // Wait briefly for camera hardware to release properly
                    kotlinx.coroutines.delay(200)

                    startSelectedPipeline()
                    StreamState.lifecycleState.set(LifecycleState.STREAMING)
                    StreamState.streaming.set(true)
                    Log.d(TAG, "Camera REBOUND to STREAMING")
                    AppLogger.i("Camera", "Camera rebound successfully")
                } catch (e: Exception) {
                    handleCameraError("Camera rebind failed", e)
                }
            }
        }
    }

    private fun recoverCamera() {
        Log.d(TAG, "Camera RECOVERY requested")
        AppLogger.i("Camera", "Camera recovery requested")
        lifecycleScope.launch {
            cameraMutex.withLock {
                StreamState.lifecycleState.set(LifecycleState.STOPPING)
                StreamState.streaming.set(false)
                try {
                    mjpegStreamer.stop()
                    h264Streamer.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping during recovery", e)
                }

                kotlinx.coroutines.delay(500) // Ensure hardware teardown

                StreamState.lifecycleState.set(LifecycleState.STARTING)
                StreamState.lastError.set("")
                StreamState.streaming.set(true)
                try {
                    startSelectedPipeline()
                    StreamState.lifecycleState.set(LifecycleState.STREAMING)
                } catch (e: Exception) {
                    handleCameraError("Failed to start camera during recovery", e)
                }
            }
        }
    }

    private fun handleCameraError(message: String, e: Exception) {
        val errText = "${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, "$message: $errText", e)
        AppLogger.e("Camera", "$message: $errText")
        StreamState.lastError.set(errText)
        StreamState.lifecycleState.set(LifecycleState.ERROR)
        StreamState.streaming.set(false)
    }

    /** Starts the requested path and transparently falls back when AVC surface
     * capture cannot be created. The requested setting is retained so a later
     * reconnect/rebind can retry hardware H.264. */
    private suspend fun startSelectedPipeline() {
        if (StreamState.streamMode.get() != "h264") {
            StreamState.fallbackReason.set("")
            StreamState.fallbackUsed.set(false)
            mjpegStreamer.start()
            StreamState.activeStreamMode.set("mjpeg")
            return
        }
        try {
            h264Streamer.start()
            StreamState.activeStreamMode.set("h264")
        } catch (h264Error: Exception) {
            val reason = "H.264 unavailable: ${h264Error.message}; using MJPEG"
            AppLogger.w("H264", reason)
            StreamState.fallbackReason.set(reason)
            StreamState.fallbackUsed.set(true)
            mjpegStreamer.start()
            StreamState.activeStreamMode.set("mjpeg")
        }
    }

    private fun applySettingsPatch(req: UpdateSettingsRequest, source: String?) {
        var requiresRebind = false
        var requiresSettingsSave = false

        // Record the desktop apply id (if any) so status.appliedVersion reflects
        // that this change has been applied — the desktop uses it to know when it
        // is safe to trust incoming status for stream-shaping fields.
        req.applyId?.let { StreamState.appliedSettingsVersion.set(it) }

        AppLogger.i("System", "Settings patch received from ${source ?: "unknown"}")

        if (req.cameraId != null || req.streamMode != null || req.width != null || req.height != null || req.fps != null) {
            h264Streamer.resetAdaptiveProfile()
            lowH264Windows = 0
        }

        // --- Camera-Affecting Settings (Rebind) ---
        req.cameraId?.let { StreamState.cameraId.set(it); requiresRebind = true; requiresSettingsSave = true }
        req.streamMode?.let { StreamState.streamMode.set(it); requiresRebind = true; requiresSettingsSave = true }
        req.h264Bitrate?.let {
            StreamState.h264Bitrate.set(it)
            requiresSettingsSave = true
            // Prefer applying the bitrate to the live encoder (no stream
            // interruption). Only rebind when H.264 is actually streaming and
            // the dynamic update failed; in MJPEG mode the value simply takes
            // effect at the next H.264 start.
            if (StreamState.activeStreamMode.get() == "h264" &&
                StreamState.lifecycleState.get() == LifecycleState.STREAMING &&
                !h264Streamer.updateBitrate(it)
            ) {
                requiresRebind = true
            }
        }
        req.h264KeyframeInterval?.let {
            StreamState.h264KeyframeInterval.set(it)
            requiresSettingsSave = true
            // Keyframe interval cannot be changed on a running codec.
            if (StreamState.streamMode.get() == "h264") requiresRebind = true
        }
        req.fps?.let { StreamState.fps.set(it.coerceIn(1, 120)); requiresRebind = true; requiresSettingsSave = true }

        if (req.width != null || req.height != null) {
            val finalW = req.width ?: StreamState.width.get()
            val finalH = req.height ?: StreamState.height.get()
            StreamState.width.set(finalW)
            StreamState.height.set(finalH)
            requiresRebind = true
            requiresSettingsSave = true
        }

        if (req.outputWidth != null || req.outputHeight != null) {
            val finalOW = req.outputWidth ?: StreamState.outputWidth.get()
            val finalOH = req.outputHeight ?: StreamState.outputHeight.get()
            StreamState.outputWidth.set(finalOW)
            StreamState.outputHeight.set(finalOH)
            requiresRebind = true
            requiresSettingsSave = true
        }

        req.profile?.let { StreamState.profile.set(it); requiresRebind = true; requiresSettingsSave = true }

        // --- Display-Only & Control Settings (No Rebind) ---
        // Note: the HTTP layer only forwards accessMode/port/accessToken from
        // loopback (phone UI or USB) clients. Mode and port changes take effect
        // after the service restarts, because the server socket binds once.
        req.accessMode?.let {
            StreamState.accessMode.set(it); requiresSettingsSave = true
            AppLogger.w("Security", "Access mode set to '$it'. Restart streaming service to apply the new bind address.")
        }
        req.port?.let {
            StreamState.port.set(it); requiresSettingsSave = true
            AppLogger.w("Security", "Port set to $it. Restart streaming service to apply.")
        }
        req.accessToken?.let { StreamState.accessToken.set(it); requiresSettingsSave = true }
        req.jpegQuality?.let { StreamState.jpegQuality.set(it.coerceIn(1, 100)); requiresSettingsSave = true }
        req.previewFitMode?.let { StreamState.previewFitMode.set(it); requiresSettingsSave = true }
        req.aspectRatio?.let { StreamState.aspectRatio.set(it); requiresSettingsSave = true }
        req.zoomSpeed?.let { StreamState.zoomSpeed.set(it); requiresSettingsSave = true }
        req.displayRotation?.let { StreamState.displayRotation.set(it); requiresSettingsSave = true }
        req.mirror?.let { StreamState.mirror.set(it); requiresSettingsSave = true }
        req.localPreviewEnabled?.let {
            val was = StreamState.localPreviewEnabled.get()
            StreamState.localPreviewEnabled.set(it)
            requiresSettingsSave = true
            // Turning the phone preview ON mid-stream must (re)bind the CameraX
            // Preview use case — it is only bound when preview is enabled at bind
            // time, so without a rebind the preview surface never receives frames
            // (the "preview button does nothing" bug). Turning it OFF only
            // detaches the surface below, keeping the stream uninterrupted.
            if (it && !was) requiresRebind = true
        }
        req.targetBandwidthMbps?.let { StreamState.targetBandwidthMbps.set(it); requiresSettingsSave = true }

        // Dynamic preview surface detach. CameraX requires setSurfaceProvider to
        // run on the MAIN thread; this patch is applied from the Ktor HTTP worker
        // thread, so calling it directly threw and returned HTTP 500 when the
        // preview was disabled. Post to main and swallow any error.
        if (req.localPreviewEnabled == false) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    StreamState.previewUseCase?.setSurfaceProvider(null)
                } catch (e: Exception) {
                    AppLogger.w("Camera", "Preview detach failed: ${e.message}")
                }
            }
        }

        if (requiresSettingsSave) settingsManager.save()
        StreamState.incrementRevision(source ?: "api")

        if (requiresRebind && StreamState.lifecycleState.get() == LifecycleState.STREAMING) {
            rebindCamera()
        }
    }

    // --- Notification ---
    private fun buildNotification(ip: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE
        )
        val port = StreamState.port.get()
        val contentText = if (StreamState.accessMode.get() == "usbOnly") {
            "USB mode - localhost:$port (adb forward required)"
        } else {
            "LAN mode - http://$ip:$port (token required)"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "OpenCamBridge stream service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
