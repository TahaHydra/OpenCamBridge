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
import com.opencambridge.android.MainActivity
import com.opencambridge.android.R
import com.opencambridge.android.camera.MjpegStreamer
import com.opencambridge.android.server.ControlServer

private const val TAG = "StreamService"
private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "opencambridge_stream"

/**
 * Foreground service that owns the Ktor HTTP server and CameraX streaming pipeline.
 *
 * Extends LifecycleService so it can be passed as LifecycleOwner to CameraX.
 * Started by MainActivity via startForegroundService().
 * Stopped via the "Stop" notification action or MainActivity calling stopService().
 */
class StreamService : LifecycleService() {

    private lateinit var controlServer: ControlServer
    private lateinit var mjpegStreamer: MjpegStreamer

    companion object {
        const val ACTION_STOP = "com.opencambridge.android.ACTION_STOP"

        fun startIntent(context: Context) =
            Intent(context, StreamService::class.java)

        fun stopIntent(context: Context) =
            Intent(context, StreamService::class.java).apply {
                action = ACTION_STOP
            }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mjpegStreamer = MjpegStreamer(
            context = applicationContext,
            lifecycleOwner = this  // LifecycleService implements LifecycleOwner
        )

        controlServer = ControlServer(
            context = applicationContext,
            onStreamStart = {
                Log.d(TAG, "Stream start requested")
                mjpegStreamer.start()
            },
            onStreamStop = {
                Log.d(TAG, "Stream stop requested")
                mjpegStreamer.stop()
            },
            onCameraSwitch = { newId ->
                Log.d(TAG, "Camera switch requested: $newId")
                mjpegStreamer.switchCamera(newId)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)  // Required by LifecycleService
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        val ip = NetworkUtils.getWifiIpAddress(applicationContext) ?: "device-ip"
        startForeground(NOTIFICATION_ID, buildNotification(ip))

        controlServer.start()
        // Auto-start the stream so /stream.mjpeg works immediately
        mjpegStreamer.start()

        Log.d(TAG, "StreamService started. Server on port 8080.")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "StreamService destroying")
        mjpegStreamer.stop()
        controlServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("http://$ip:8080")
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
        ).apply {
            description = "OpenCamBridge stream service"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
