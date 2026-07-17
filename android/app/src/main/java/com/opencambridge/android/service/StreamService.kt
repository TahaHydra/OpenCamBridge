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
import com.opencambridge.android.camera.H264Capabilities
import com.opencambridge.android.camera.H264ModeDto
import com.opencambridge.android.camera.MjpegStreamer
import com.opencambridge.android.camera.CameraRepository
import com.opencambridge.android.server.ControlServer
import com.opencambridge.android.server.UpdateSettingsRequest
import com.opencambridge.android.state.AppLogger
import com.opencambridge.android.state.LifecycleState
import com.opencambridge.android.state.SettingsManager
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

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
    private lateinit var pipelineController: PipelineController
    private var streamWakeLock: android.os.PowerManager.WakeLock? = null
    private val cameraRepository by lazy { CameraRepository(applicationContext) }
    private val appliedRequestResults = object : LinkedHashMap<String, PipelineResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PipelineResult>?): Boolean = size > 128
    }

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
        pipelineController = PipelineController(lifecycleScope, ::handlePipelineCommand)

        controlServer = ControlServer(
            context = applicationContext,
            settingsManager = settingsManager,
            h264Streamer = h264Streamer,
            onStartCamera = { pipelineController.submit(PipelineCommand.Start()) },
            onStopCamera = { pipelineController.submit(PipelineCommand.Stop()) },
            onApplySettingsPatch = { req, source -> pipelineController.submit(PipelineCommand.ApplySettings(req, source)) },
            onSetZoomRatio = { ratio ->
                if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setZoomRatio(ratio) else mjpegStreamer.setZoomRatio(ratio)
            },
            onSetLinearZoom = { linear ->
                if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setLinearZoom(linear) else mjpegStreamer.setLinearZoom(linear)
            },
            onSetTorch = { enabled ->
                if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setTorch(enabled) else mjpegStreamer.setTorch(enabled)
            },
            onRecoverCamera = { pipelineController.submit(PipelineCommand.Recover()) }
        )

        // Expose the same control handlers in-process so the phone UI can call
        // them directly instead of POSTing to its own loopback server.
        ServiceBridge.applyPatch = { req, source ->
            pipelineController.enqueue(lifecycleScope, PipelineCommand.ApplySettings(req, source))
        }
        ServiceBridge.setTorch = { enabled ->
            if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setTorch(enabled) else mjpegStreamer.setTorch(enabled)
        }
        ServiceBridge.setLinearZoom = { linear ->
            if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setLinearZoom(linear) else mjpegStreamer.setLinearZoom(linear)
        }
        ServiceBridge.setZoomRatio = { ratio ->
            if (StreamState.activeStreamMode.get() == "h264") h264Streamer.setZoomRatio(ratio) else mjpegStreamer.setZoomRatio(ratio)
        }
        ServiceBridge.startCamera = { pipelineController.enqueue(lifecycleScope, PipelineCommand.Start()) }
        ServiceBridge.stopCamera = { pipelineController.enqueue(lifecycleScope, PipelineCommand.Stop()) }
        ServiceBridge.recoverCamera = { pipelineController.enqueue(lifecycleScope, PipelineCommand.Recover()) }
        ServiceBridge.previewSurfaceChanged = { attached ->
            pipelineController.enqueue(lifecycleScope, PipelineCommand.PreviewSurfaceChanged(attached))
        }

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
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            AppLogger.w(
                "Power",
                "OEM battery optimization is active. The foreground camera service and stream wake lock are configured, but a restrictive OEM may still require the user to exempt OpenCamBridge for long locked-screen sessions."
            )
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
        pipelineController.enqueue(lifecycleScope, PipelineCommand.Start())

        // Start bandwidth monitoring
        if (monitorJob?.isActive != true) monitorJob = lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(2000)

                if (StreamState.h264Failed.getAndSet(false) && StreamState.activeStreamMode.get() == "h264") {
                    pipelineController.submit(
                        PipelineCommand.RuntimeH264Failure(
                            StreamState.capturePathError.get().ifBlank { "hardware encoder/capture path failed at runtime" }
                        )
                    )
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
                        val reason = "${StreamState.encodedWidth.get()}x${StreamState.encodedHeight.get()}@$target could not sustain $target FPS"
                        pipelineController.submit(PipelineCommand.AdaptiveDowngrade(reason))
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
                        StreamState.publishConfig(StreamState.currentConfig().copy(jpegQuality = (currentQ - 3).coerceAtLeast(40)))
                        settingsManager.save()
                        StreamState.incrementRevision("auto-bandwidth")
                    } else if (mbps < targetBandwidth * 0.75 && currentQ < 95) {
                        StreamState.publishConfig(StreamState.currentConfig().copy(jpegQuality = (currentQ + 2).coerceAtMost(95)))
                        settingsManager.save()
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
        controlServer.stop()
        runBlocking(Dispatchers.IO) {
            try { pipelineController.submit(PipelineCommand.Stop()) } catch (_: Exception) {}
        }
        pipelineController.close()
        if (streamWakeLock?.isHeld == true) streamWakeLock?.release()
        streamWakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // --- State Machine & Controller Methods ---

    private suspend fun handlePipelineCommand(command: PipelineCommand): PipelineResult = when (command) {
        is PipelineCommand.Start -> startCameraNow()
        is PipelineCommand.Stop -> stopCameraNow()
        is PipelineCommand.Recover -> recoverCameraNow()
        is PipelineCommand.ApplySettings -> applySettingsPatch(command.request, command.source)
        is PipelineCommand.RuntimeH264Failure -> switchToMjpegNow(command.reason)
        is PipelineCommand.AdaptiveDowngrade -> adaptiveDowngradeNow(command.reason)
        is PipelineCommand.PreviewSurfaceChanged -> previewSurfaceChangedNow(command.attached)
    }

    private suspend fun startCameraNow(): PipelineResult {
        val currentState = StreamState.lifecycleState.get()
        if (currentState == LifecycleState.STREAMING) return pipelineResult("Camera is already streaming")
        StreamState.lifecycleState.set(LifecycleState.STARTING)
        StreamState.lastError.set("")
        StreamState.streaming.set(false)
        beginPipelineGeneration()
        acquireStreamWakeLock()
        Log.d(TAG, "Camera STARTING")
        AppLogger.i("Camera", "Camera starting (generation ${StreamState.pipelineGeneration.get()})")
        return try {
            startSelectedPipeline()
            StreamState.lifecycleState.set(LifecycleState.STREAMING)
            StreamState.streaming.set(true)
            AppLogger.i("Camera", "Camera streaming successfully")
            pipelineResult("Camera streaming")
        } catch (e: Exception) {
            releaseStreamWakeLock()
            handleCameraError("Failed to start camera", e)
        }
    }

    private suspend fun stopCameraNow(): PipelineResult {
        if (StreamState.lifecycleState.get() == LifecycleState.STOPPED) return pipelineResult("Camera is already stopped")
        StreamState.lifecycleState.set(LifecycleState.STOPPING)
        StreamState.streaming.set(false)
        StreamState.pipelineGeneration.incrementAndGet()
        AppLogger.i("Camera", "Camera stopping")
        return try {
            mjpegStreamer.stop()
            h264Streamer.stop()
            resetPipelineMetrics()
            StreamState.lifecycleState.set(LifecycleState.STOPPED)
            releaseStreamWakeLock()
            AppLogger.i("Camera", "Camera stopped cleanly")
            pipelineResult("Camera stopped")
        } catch (e: Exception) {
            releaseStreamWakeLock()
            handleCameraError("Failed to stop camera cleanly", e)
        }
    }

    private suspend fun rebindCameraNow(reason: String): PipelineResult {
        if (StreamState.lifecycleState.get() != LifecycleState.STREAMING &&
            StreamState.lifecycleState.get() != LifecycleState.ERROR
        ) return pipelineResult("Settings saved; camera is not currently streaming")
        StreamState.lifecycleState.set(LifecycleState.REBINDING)
        StreamState.streaming.set(false)
        beginPipelineGeneration()
        acquireStreamWakeLock()
        AppLogger.i("Camera", "Camera rebinding: $reason")
        return try {
            mjpegStreamer.stop()
            h264Streamer.stop()
            startSelectedPipeline()
            StreamState.lifecycleState.set(LifecycleState.STREAMING)
            StreamState.streaming.set(true)
            AppLogger.i("Camera", "Camera rebound successfully")
            pipelineResult("Settings applied and camera rebound")
        } catch (e: Exception) {
            handleCameraError("Camera rebind failed", e)
        }
    }

    private suspend fun recoverCameraNow(): PipelineResult {
        AppLogger.i("Camera", "Camera recovery requested")
        StreamState.lifecycleState.set(LifecycleState.STOPPING)
        StreamState.streaming.set(false)
        acquireStreamWakeLock()
        return try {
            mjpegStreamer.stop()
            h264Streamer.stop()
            StreamState.lifecycleState.set(LifecycleState.STARTING)
            StreamState.lastError.set("")
            beginPipelineGeneration()
            startSelectedPipeline()
            StreamState.lifecycleState.set(LifecycleState.STREAMING)
            StreamState.streaming.set(true)
            pipelineResult("Camera recovered")
        } catch (e: Exception) {
            handleCameraError("Failed to recover camera", e)
        }
    }

    private suspend fun switchToMjpegNow(reason: String): PipelineResult {
        if (StreamState.activeStreamMode.get() != "h264") return pipelineResult("H.264 fallback already inactive")
        StreamState.lifecycleState.set(LifecycleState.REBINDING)
        StreamState.streaming.set(false)
        beginPipelineGeneration()
        val fallback = "H.264 failed: $reason; using MJPEG compatibility"
        AppLogger.w("H264", fallback)
        return try {
            h264Streamer.stop()
            mjpegStreamer.start()
            StreamState.activeStreamMode.set("mjpeg")
            StreamState.fallbackReason.set(fallback)
            StreamState.fallbackUsed.set(true)
            StreamState.lifecycleState.set(LifecycleState.STREAMING)
            StreamState.streaming.set(true)
            pipelineResult(fallback)
        } catch (e: Exception) {
            handleCameraError("H.264 to MJPEG fallback failed", e)
        }
    }

    private suspend fun adaptiveDowngradeNow(reason: String): PipelineResult {
        val next = h264Streamer.prepareAdaptiveDowngrade()
            ?: return switchToMjpegNow("$reason; no lower sustainable H.264 profile")
        StreamState.fallbackReason.set("$reason; adapting to ${next.width}x${next.height}@${next.fps}")
        StreamState.fallbackUsed.set(true)
        return rebindCameraNow(StreamState.fallbackReason.get())
    }

    private suspend fun previewSurfaceChangedNow(attached: Boolean): PipelineResult {
        val config = StreamState.currentConfig()
        if (!config.localPreviewEnabled || StreamState.activeStreamMode.get() != "h264" ||
            StreamState.lifecycleState.get() != LifecycleState.STREAMING
        ) {
            return pipelineResult("Preview surface ${if (attached) "attached" else "detached"}; no H.264 rebind required")
        }
        return rebindCameraNow("phone preview surface ${if (attached) "attached" else "detached"}")
    }

    private fun handleCameraError(message: String, e: Exception): PipelineResult {
        val errText = "${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, "$message: $errText", e)
        AppLogger.e("Camera", "$message: $errText")
        StreamState.lastError.set(errText)
        StreamState.lifecycleState.set(LifecycleState.ERROR)
        StreamState.streaming.set(false)
        releaseStreamWakeLock()
        return pipelineResult("$message: $errText", PipelineResultCode.FAILED)
    }

    private fun acquireStreamWakeLock() {
        if (streamWakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        streamWakeLock = power.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "OpenCamBridge:StreamingPipeline"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseStreamWakeLock() {
        if (streamWakeLock?.isHeld == true) streamWakeLock?.release()
        streamWakeLock = null
    }

    private fun pipelineResult(message: String, code: PipelineResultCode = PipelineResultCode.OK) = PipelineResult(
        code = code,
        message = message,
        revision = StreamState.revision.get(),
        generation = StreamState.pipelineGeneration.get(),
        lifecycleState = StreamState.lifecycleState.get().name
    )

    private fun beginPipelineGeneration() {
        StreamState.pipelineGeneration.incrementAndGet()
        resetPipelineMetrics()
    }

    private fun resetPipelineMetrics() {
        StreamState.actualFps.set(0)
        StreamState.captureFps.set(0)
        StreamState.encodedFps.set(0)
        StreamState.encodedBitrate.set(0)
        StreamState.gpuBridgeFps.set(0)
        StreamState.latestFrameRevision.set(0)
        StreamState.androidEncodeMsAvg.set(0.0)
        StreamState.yuvMsAvg.set(0.0)
        StreamState.jpegMsAvg.set(0.0)
        StreamState.rotateMsAvg.set(0.0)
        StreamState.capturePathError.set("")
    }

    /** Starts the requested path and transparently falls back when AVC surface
     * capture cannot be created. The requested setting is retained so a later
     * reconnect/rebind can retry hardware H.264. */
    private suspend fun startSelectedPipeline() {
        val config = StreamState.currentConfig()
        if (config.streamMode != "h264") {
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

    private suspend fun applySettingsPatch(req: UpdateSettingsRequest, source: String?): PipelineResult {
        req.requestId?.let { appliedRequestResults[it] }?.let { return it }
        val expectedRevision = req.baseRevision ?: req.clientRevision
        val currentRevision = StreamState.revision.get()
        if (expectedRevision != null && expectedRevision != currentRevision) {
            return rememberRequest(req.requestId, pipelineResult(
                "Revision conflict: client base=$expectedRevision, authoritative=$currentRevision",
                PipelineResultCode.CONFLICT
            ))
        }
        val previous = StreamState.currentConfig()
        val next = previous.copy(
            accessMode = req.accessMode ?: previous.accessMode,
            port = req.port ?: previous.port,
            accessToken = req.accessToken ?: previous.accessToken,
            streamMode = req.streamMode ?: previous.streamMode,
            h264Bitrate = req.h264Bitrate ?: previous.h264Bitrate,
            h264KeyframeInterval = req.h264KeyframeInterval ?: previous.h264KeyframeInterval,
            cameraId = req.cameraId ?: previous.cameraId,
            width = req.width ?: previous.width,
            height = req.height ?: previous.height,
            outputWidth = req.outputWidth ?: previous.outputWidth,
            outputHeight = req.outputHeight ?: previous.outputHeight,
            profile = req.profile ?: previous.profile,
            jpegQuality = req.jpegQuality?.coerceIn(1, 100) ?: previous.jpegQuality,
            fps = req.fps ?: previous.fps,
            previewFitMode = req.previewFitMode ?: previous.previewFitMode,
            aspectRatio = req.aspectRatio ?: previous.aspectRatio,
            zoomSpeed = req.zoomSpeed ?: previous.zoomSpeed,
            displayRotation = req.displayRotation ?: previous.displayRotation,
            mirror = req.mirror ?: previous.mirror,
            localPreviewEnabled = req.phonePreviewEnabled ?: req.localPreviewEnabled ?: previous.localPreviewEnabled,
            targetBandwidthMbps = req.targetBandwidthMbps ?: previous.targetBandwidthMbps
        )
        val touchesPath = req.cameraId != null || req.streamMode != null || req.width != null ||
            req.height != null || req.fps != null || req.profile != null
        validateRequestedPath(next, touchesPath)?.let { rejection ->
            return rememberRequest(req.requestId, pipelineResult(rejection, PipelineResultCode.UNPROCESSABLE))
        }
        if (next.port !in 1024..65535) {
            return rememberRequest(req.requestId, pipelineResult("Port ${next.port} is outside 1024..65535", PipelineResultCode.UNPROCESSABLE))
        }
        if (next.h264KeyframeInterval != 1) {
            return rememberRequest(req.requestId, pipelineResult("Webcam H.264 keyframe interval must be one second", PipelineResultCode.UNPROCESSABLE))
        }
        val captureChanged = previous.cameraId != next.cameraId || previous.streamMode != next.streamMode ||
            previous.width != next.width || previous.height != next.height || previous.fps != next.fps ||
            previous.profile != next.profile || previous.h264KeyframeInterval != next.h264KeyframeInterval ||
            previous.outputWidth != next.outputWidth || previous.outputHeight != next.outputHeight ||
            previous.localPreviewEnabled != next.localPreviewEnabled
        var requiresRebind = captureChanged

        // Record the desktop apply id (if any) so status.appliedVersion reflects
        // that this change has been applied — the desktop uses it to know when it
        // is safe to trust incoming status for stream-shaping fields.
        req.applyId?.let { StreamState.appliedSettingsVersion.set(it) }

        AppLogger.i("System", "Settings patch received from ${source ?: "unknown"}")

        if (captureChanged) {
            h264Streamer.resetAdaptiveProfile()
            lowH264Windows = 0
        }
        StreamState.publishConfig(next)
        if (previous.accessMode != next.accessMode || previous.port != next.port) {
            AppLogger.w("Security", "Bind settings changed; restart the service to bind ${next.accessMode}:${next.port}")
        }
        if (!requiresRebind && previous.h264Bitrate != next.h264Bitrate &&
            StreamState.activeStreamMode.get() == "h264" &&
            StreamState.lifecycleState.get() == LifecycleState.STREAMING &&
            !h264Streamer.updateBitrate(next.h264Bitrate)
        ) {
            requiresRebind = true
        }

        // Dynamic preview surface detach. CameraX requires setSurfaceProvider to
        // run on the MAIN thread; this patch is applied from the Ktor HTTP worker
        // thread, so calling it directly threw and returned HTTP 500 when the
        // preview was disabled. Post to main and swallow any error.
        if (req.phonePreviewEnabled == false || req.localPreviewEnabled == false) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    StreamState.previewUseCase?.setSurfaceProvider(null)
                } catch (e: Exception) {
                    AppLogger.w("Camera", "Preview detach failed: ${e.message}")
                }
            }
        }

        if (next != previous) settingsManager.save()
        StreamState.incrementRevision(source ?: "api")

        val result = if (requiresRebind && StreamState.lifecycleState.get() == LifecycleState.STREAMING) {
            rebindCameraNow("settings revision ${StreamState.revision.get()} from ${source ?: "api"}")
        } else {
            pipelineResult("Settings applied")
        }
        return rememberRequest(req.requestId, result)
    }

    private fun rememberRequest(requestId: String?, result: PipelineResult): PipelineResult {
        if (!requestId.isNullOrBlank()) appliedRequestResults[requestId] = result
        return result
    }

    /** Validate exactly the path that the resulting immutable settings snapshot
     * requests. Unsupported FPS is rejected instead of being clamped or echoed
     * back as if active. Adaptive H.264 is allowed to select the best complete
     * profile; explicit H.264 and all MJPEG requests must match exactly. */
    private fun validateRequestedPath(config: com.opencambridge.android.state.StreamConfig, touchesPath: Boolean): String? {
        if (!touchesPath) return null
        val cameraId = config.cameraId
        val width = config.width
        val height = config.height
        val fps = config.fps
        val mode = config.streamMode
        val profile = config.profile
        if (mode == "h264") {
            if (fps !in setOf(30, 60)) return "Unsupported H.264 webcam FPS $fps; selectable values are 30 or 60"
            if ((width != 1280 || height != 720) && (width != 1920 || height != 1080)) {
                return "Unsupported H.264 webcam resolution ${width}x$height"
            }
            if (profile == "adaptive") return null
            val selections = H264Capabilities.selectCandidates(
                applicationContext,
                cameraId,
                width,
                height,
                fps,
                includeAdaptiveFallbacks = false
            )
            if (selections.isNotEmpty()) return null
            val reasons = H264Capabilities.inspectPathCapabilities(
                applicationContext,
                cameraId,
                H264ModeDto(width, height, fps)
            ).joinToString("; ") { "${it.engine}: ${it.reason}" }
            return "H.264 ${width}x$height@$fps is unavailable on camera $cameraId: $reasons"
        }
        if (mode != "mjpeg") return "Unknown stream mode '$mode'"
        if (fps !in setOf(15, 30, 60)) return "Unsupported MJPEG FPS $fps; selectable values are 15, 30, or 60"
        val camera = cameraRepository.listCameras().firstOrNull { it.id == cameraId }
            ?: return "Unknown camera '$cameraId'"
        val capability = camera.fpsByResolution.firstOrNull { it.width == width && it.height == height }
            ?: return "MJPEG ${width}x$height is unavailable on camera $cameraId"
        if (capability.maxFps < fps) {
            return "MJPEG ${width}x$height@$fps is unavailable on camera $cameraId; regular Camera2/ImageAnalysis max is ${capability.maxFps} FPS"
        }
        return null
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
