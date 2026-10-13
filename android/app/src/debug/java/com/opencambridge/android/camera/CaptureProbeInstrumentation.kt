package com.opencambridge.android.camera

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import com.opencambridge.android.MainActivity
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** No network, recordings or settings writes. Runs as the foreground debug app. */
class CaptureProbeInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) { this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        var activity: Activity? = null
        val output = Bundle()
        val thread = HandlerThread("CaptureProbe").apply { start() }
        val handler = Handler(thread.looper)
        val device = AtomicReference<CameraDevice?>()
        val session = AtomicReference<CameraCaptureSession?>()
        var encoder: MediaCodec? = null
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val cameraId = arguments.getString("cameraId", "0")
            if (arguments.getString("pipeline", "false").toBoolean()) {
                val previous = StreamState.currentConfig()
                val streamer = H264Streamer(targetContext, activity as androidx.lifecycle.LifecycleOwner)
                try {
                    StreamState.publishConfig(previous.copy(cameraId = cameraId, width = 1920, height = 1080,
                        fps = 60, streamMode = "h264", profile = "quality", localPreviewEnabled = false))
                    runBlocking {
                        streamer.start()
                        delay(1500)
                        check(StreamState.selectedFps.get() == 60) { "Production pipeline selected ${StreamState.selectedFps.get()}: ${StreamState.captureVerificationReason.get()}" }
                        val first = "selected=${StreamState.selectedFps.get()} capture=${StreamState.captureFps.get()} encoded=${StreamState.encodedFps.get()} state=${StreamState.captureVerification.get()}"
                        streamer.stop()
                        val restartAt = SystemClock.elapsedRealtime()
                        streamer.start()
                        val cachedStartMs = SystemClock.elapsedRealtime() - restartAt
                        check(StreamState.selectedFps.get() == 60 && StreamState.captureVerification.get() == "verified")
                        output.putString("probe", "PIPELINE_PASS camera=$cameraId $first cachedStartMs=$cachedStartMs reason=${StreamState.captureVerificationReason.get()}")
                    }
                } finally {
                    runBlocking { streamer.stop() }
                    StreamState.publishConfig(previous)
                }
                return
            }
            val withParameters = arguments.getString("sessionParameters", "false").toBoolean()
            val width = 1920; val height = 1080; val fps = 60
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
                it.isEncoder && !it.isSoftwareOnly && it.supportedTypes.any { type -> type.equals("video/avc", true) } &&
                    it.getCapabilitiesForType("video/avc").videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
            }
            encoder = MediaCodec.createByCodecName(codec.name)
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            val opened = CountDownLatch(1)
            val failure = AtomicReference<String?>()
            if (targetContext.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Camera permission must be granted before running this probe")
            (targetContext.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager).openCamera(cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) { device.set(camera); opened.countDown() }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); failure.set("disconnected"); opened.countDown() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); failure.set("camera error $error"); opened.countDown() }
                }, handler)
            check(opened.await(5, TimeUnit.SECONDS)) { "Camera open timed out" }
            check(failure.get() == null) { failure.get().orEmpty() }
            val camera = checkNotNull(device.get())
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            val configured = CountDownLatch(1)
            val samples = CaptureSampleWindow(SystemClock.elapsedRealtimeNanos())
            val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)),
                Executor { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(active: CameraCaptureSession) {
                        session.set(active)
                        try {
                            active.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                    samples.capture(result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0,
                                        result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0,
                                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0, SystemClock.elapsedRealtimeNanos())
                                }
                            }, handler)
                        } catch (error: Exception) { failure.set(error.toString()) }
                        configured.countDown()
                    }
                    override fun onConfigureFailed(active: CameraCaptureSession) { active.close(); failure.set("session rejected"); configured.countDown() }
                })
            if (withParameters) configuration.sessionParameters = request
            camera.createCaptureSession(configuration)
            check(configured.await(5, TimeUnit.SECONDS)) { "Session timed out" }
            check(failure.get() == null) { failure.get().orEmpty() }
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtimeNanos() + 5_000_000_000L
            while (SystemClock.elapsedRealtimeNanos() < deadline) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                        samples.encoded(info.presentationTimeUs * 1000, SystemClock.elapsedRealtimeNanos())
                    encoder.releaseOutputBuffer(index, false)
                }
            }
            val result = samples.result()
            output.putString("probe", "camera=$cameraId params=$withParameters capture=${result.captureFps} encoded=${result.encodedFps} verdict=${result.verdict} ${result.reason}")
            surface.release()
        } catch (error: Throwable) { output.putString("probe", "FAILED: $error") }
        finally {
            try { session.get()?.close() } catch (_: Exception) {}
            try { device.get()?.close() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            thread.quitSafely(); thread.join(1500)
            if (activity != null) runOnMainSync { activity.finish() }
            finish(Activity.RESULT_OK, output)
        }
    }
}
