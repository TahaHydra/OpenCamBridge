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

private const val TAG = "StreamService"
private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "opencambridge_stream"

class StreamService : LifecycleService() {

    private lateinit var controlServer: ControlServer
    private lateinit var mjpegStreamer: MjpegStreamer
    private lateinit var h264Streamer: H264Streamer
    private lateinit var settingsManager: SettingsManager

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
                if (StreamState.streamMode.get() == "h264") h264Streamer.setZoomRatio(ratio) else mjpegStreamer.setZoomRatio(ratio) 
            },
            onSetLinearZoom = { linear -> 
                if (StreamState.streamMode.get() == "h264") h264Streamer.setLinearZoom(linear) else mjpegStreamer.setLinearZoom(linear) 
            },
            onSetTorch = { enabled -> 
                if (StreamState.streamMode.get() == "h264") h264Streamer.setTorch(enabled) else mjpegStreamer.setTorch(enabled) 
            },
            onRecoverCamera = { recoverCamera() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        val ip = NetworkUtils.getWifiIpAddress(applicationContext) ?: "device-ip"
        startForeground(NOTIFICATION_ID, buildNotification(ip))

        controlServer.start()
        val port = StreamState.port.get()
        Log.d(TAG, "StreamService started. Server on port $port.")
        AppLogger.i("System", "StreamService started on port $port")
        
        // Auto-start stream
        startCamera()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "StreamService destroying")
        AppLogger.i("System", "StreamService stopping completely")
        stopCamera()
        controlServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // --- State Machine & Controller Methods ---

    private fun startCamera() {
        val currentState = StreamState.lifecycleState.get()
        if (currentState == LifecycleState.STARTING || currentState == LifecycleState.STREAMING || currentState == LifecycleState.REBINDING) {
            return
        }

        StreamState.lifecycleState.set(LifecycleState.STARTING)
        StreamState.lastError.set("")
        StreamState.streaming.set(true)
        Log.d(TAG, "Camera STARTING")
        AppLogger.i("Camera", "Camera starting")

        lifecycleScope.launch {
            try {
                if (StreamState.streamMode.get() == "h264") {
                    h264Streamer.start()
                } else {
                    mjpegStreamer.start()
                }
                StreamState.lifecycleState.set(LifecycleState.STREAMING)
                Log.d(TAG, "Camera STREAMING")
                AppLogger.i("Camera", "Camera streaming successfully")
            } catch (e: Exception) {
                handleCameraError("Failed to start camera", e)
            }
        }
    }

    private fun stopCamera() {
        StreamState.lifecycleState.set(LifecycleState.STOPPING)
        StreamState.streaming.set(false)
        Log.d(TAG, "Camera STOPPING")
        AppLogger.i("Camera", "Camera stopping")

        lifecycleScope.launch {
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

    private fun rebindCamera() {
        val currentState = StreamState.lifecycleState.get()
        if (currentState != LifecycleState.STREAMING && currentState != LifecycleState.ERROR) {
            return
        }
        
        Log.d(TAG, "Camera REBINDING")
        AppLogger.i("Camera", "Camera rebinding due to setting change")
        StreamState.lifecycleState.set(LifecycleState.REBINDING)
        StreamState.streaming.set(false)
        
        lifecycleScope.launch {
            try {
                mjpegStreamer.stop()
                h264Streamer.stop()
                // Wait briefly for camera hardware to release properly
                kotlinx.coroutines.delay(200)
                
                if (StreamState.streamMode.get() == "h264") {
                    h264Streamer.start()
                } else {
                    mjpegStreamer.start()
                }
                StreamState.lifecycleState.set(LifecycleState.STREAMING)
                StreamState.streaming.set(true)
                Log.d(TAG, "Camera REBOUND to STREAMING")
                AppLogger.i("Camera", "Camera rebound successfully")
            } catch (e: Exception) {
                handleCameraError("Camera rebind failed", e)
            }
        }
    }

    private fun recoverCamera() {
        Log.d(TAG, "Camera RECOVERY requested")
        AppLogger.i("Camera", "Camera recovery requested")
        stopCamera()
        lifecycleScope.launch {
            kotlinx.coroutines.delay(500) // Ensure hardware teardown
            startCamera()
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

    private fun applySettingsPatch(req: UpdateSettingsRequest, source: String?) {
        var requiresRebind = false
        var requiresSettingsSave = false
        
        AppLogger.i("System", "Settings patch received from ${source ?: "unknown"}")

        // --- Camera-Affecting Settings (Rebind) ---
        req.cameraId?.let { StreamState.cameraId.set(it); requiresRebind = true; requiresSettingsSave = true }
        req.streamMode?.let { StreamState.streamMode.set(it); requiresRebind = true; requiresSettingsSave = true }
        req.h264Bitrate?.let { StreamState.h264Bitrate.set(it); requiresRebind = true; requiresSettingsSave = true }
        req.h264KeyframeInterval?.let { StreamState.h264KeyframeInterval.set(it); requiresRebind = true; requiresSettingsSave = true }
        req.fps?.let { StreamState.fps.set(it.coerceIn(1, 120)); requiresRebind = true; requiresSettingsSave = true }

        if (req.width != null || req.height != null) {
            val finalW = req.width ?: StreamState.width.get()
            val finalH = req.height ?: StreamState.height.get()
            StreamState.width.set(finalW)
            StreamState.height.set(finalH)
            requiresRebind = true
            requiresSettingsSave = true
        }
        
        // --- Display-Only & Control Settings (No Rebind) ---
        req.accessMode?.let { StreamState.accessMode.set(it); requiresSettingsSave = true }
        req.port?.let { StreamState.port.set(it); requiresSettingsSave = true }
        req.accessToken?.let { StreamState.accessToken.set(it); requiresSettingsSave = true }
        req.jpegQuality?.let { StreamState.jpegQuality.set(it.coerceIn(1, 100)); requiresSettingsSave = true }
        req.previewFitMode?.let { StreamState.previewFitMode.set(it); requiresSettingsSave = true }
        req.aspectRatio?.let { StreamState.aspectRatio.set(it); requiresSettingsSave = true }
        req.zoomSpeed?.let { StreamState.zoomSpeed.set(it); requiresSettingsSave = true }
        req.displayRotation?.let { StreamState.displayRotation.set(it); requiresSettingsSave = true }
        req.mirror?.let { StreamState.mirror.set(it); requiresSettingsSave = true }
        req.localPreviewEnabled?.let { StreamState.localPreviewEnabled.set(it); requiresSettingsSave = true }

        // Dynamic preview surface detach
        if (req.localPreviewEnabled == false) {
            StreamState.previewUseCase?.setSurfaceProvider(null)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("http://$ip:$port")
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
