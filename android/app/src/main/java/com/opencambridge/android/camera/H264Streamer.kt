package com.opencambridge.android.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import com.opencambridge.android.protocol.Ocb2
import com.opencambridge.android.state.AppLogger
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Zero-copy H.264 capture path: Camera2 writes directly into a hardware
 * MediaCodec input Surface. Kotlin never sees or converts a YUV camera frame.
 */
class H264Streamer(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") lifecycleOwner: androidx.lifecycle.LifecycleOwner
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val scope = CoroutineScope(Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private val clients = CopyOnWriteArrayList<Channel<ByteArray>>()
    // The sequence carried by video records is frame-only. Heartbeats and
    // configuration records reuse the latest value, so transport bookkeeping
    // cannot look like dropped camera frames on Windows.
    private val frameSequence = AtomicLong(0)
    private val running = AtomicBoolean(false)

    private var cameraThread: HandlerThread? = null
    private var codecThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var codecHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    @Volatile private var codec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var selection: H264EncoderSelection? = null
    private var adaptiveMode: H264ModeDto? = null
    private var codecConfig: ByteArray? = null
    private var streamInfo: ByteArray? = null
    private var partialAccessUnit: ByteArrayOutputStream? = null
    private var partialKeyframe = false
    private var partialPresentationUs = 0L
    private var zoomJob: Job? = null
    private var heartbeatJob: Job? = null

    private var captureWindowStartNs = 0L
    private var captureWindowFrames = 0
    private var encodedWindowStartNs = 0L
    private var encodedWindowFrames = 0
    private var encodedWindowBytes = 0L

    suspend fun start() = lifecycleMutex.withLock {
        stopInternal(sendEnd = false)
        StreamState.rebindInProgress.set(true)
        try {
            val cameraId = StreamState.cameraId.get()
            val requested = adaptiveMode
            val chosen = H264Capabilities.select(
                context, cameraId, requested?.width ?: StreamState.width.get(), requested?.height ?: StreamState.height.get(), requested?.fps ?: StreamState.fps.get()
            ) ?: throw IllegalStateException("No hardware H.264 Camera2 surface profile is available for camera $cameraId")
            selection = chosen

            cameraThread = HandlerThread("OCB2-Camera2").apply { start() }
            codecThread = HandlerThread("OCB2-MediaCodec").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
            codecHandler = Handler(codecThread!!.looper)

            configureCodec(chosen)
            publishSelection(chosen)
            captureWindowStartNs = 0L
            captureWindowFrames = 0
            val device = openCamera(cameraId)
            camera = device
            createSession(device, chosen)
            running.set(true)
            StreamState.activeStreamMode.set("h264")
            StreamState.h264Failed.set(false)
            heartbeatJob = scope.launch {
                while (running.get()) {
                    delay(1_000)
                    if (running.get()) broadcast(Ocb2.record(Ocb2.TYPE_HEARTBEAT, 0, currentSequence(), SystemClock.elapsedRealtimeNanos(), 0))
                }
            }
            StreamState.streaming.set(true)
            AppLogger.i("H264", "${chosen.codecName}: ${chosen.mode.width}x${chosen.mode.height}@${chosen.mode.fps}, Camera2 surface input")
        } catch (e: Exception) {
            stopInternal(sendEnd = false)
            StreamState.fallbackReason.set("H.264 startup failed: ${e.message}")
            throw e
        } finally {
            StreamState.rebindInProgress.set(false)
        }
    }

    suspend fun stop() = lifecycleMutex.withLock { stopInternal(sendEnd = true) }

    private fun stopInternal(sendEnd: Boolean) {
        val wasRunning = running.getAndSet(false)
        if (sendEnd && wasRunning) broadcast(
            Ocb2.record(Ocb2.TYPE_END_OF_STREAM, Ocb2.FLAG_END_OF_STREAM, currentSequence(), SystemClock.elapsedRealtimeNanos(), 0)
        )
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        session?.close(); session = null
        camera?.close(); camera = null
        requestBuilder = null
        // Invalidate this generation before stopping it. Qualcomm can deliver
        // callbacks queued by stop/release after the replacement codec starts;
        // callback identity checks below keep those events isolated.
        val oldCodec = codec
        codec = null
        try { oldCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { oldCodec?.stop() } catch (_: Exception) {}
        try { oldCodec?.release() } catch (_: Exception) {}
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
        codecConfig = null
        streamInfo = null
        partialAccessUnit = null
        partialKeyframe = false
        partialPresentationUs = 0L
        heartbeatJob?.cancel(); heartbeatJob = null
        zoomJob?.cancel(); zoomJob = null
        cameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
        codecThread?.quitSafely(); codecThread = null; codecHandler = null
        StreamState.streaming.set(false)
        clients.forEach { it.close() }
        clients.clear()
        StreamState.h264ClientCount.set(0)
    }

    fun subscribe(): Channel<ByteArray> {
        // Access units are indivisible. If a client cannot keep up, disconnect
        // it and reconnect at a fresh config+IDR instead of dropping references.
        val channel = Channel<ByteArray>(capacity = 3)
        clients.add(channel)
        StreamState.h264ClientCount.set(clients.size)
        streamInfo?.let(channel::trySend)
        codecConfig?.let { config ->
            channel.trySend(Ocb2.record(Ocb2.TYPE_CODEC_CONFIG, Ocb2.FLAG_CODEC_CONFIG or Ocb2.FLAG_DISCONTINUITY,
                currentSequence(), SystemClock.elapsedRealtimeNanos(), 0, config))
        }
        requestKeyFrame()
        return channel
    }

    fun unsubscribe(channel: Channel<ByteArray>) {
        clients.remove(channel)
        StreamState.h264ClientCount.set(clients.size)
        channel.close()
    }

    fun requestKeyFrame() {
        val c = codec ?: return
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "Keyframe request failed", e)
        }
    }

    fun updateBitrate(requested: Int): Boolean {
        val c = codec ?: return false
        val bitrate = boundedBitrate(requested, selection ?: return false)
        return try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
            StreamState.actualBitrate.set(bitrate)
            true
        } catch (_: Exception) { false }
    }

    private fun configureCodec(chosen: H264EncoderSelection) {
        val bitrate = boundedBitrate(StreamState.h264Bitrate.get(), chosen)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, chosen.mode.width, chosen.mode.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, chosen.mode.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val c = MediaCodec.createByCodecName(chosen.codecName)
        c.setCallback(codecCallback, codecHandler)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = c.createInputSurface()
        // Publish the generation before start(), because asynchronous format
        // and error callbacks are allowed as soon as the codec starts.
        codec = c
        try {
            c.start()
        } catch (e: Exception) {
            if (codec === c) codec = null
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        StreamState.actualBitrate.set(bitrate)
        encodedWindowFrames = 0
        encodedWindowBytes = 0L
        encodedWindowStartNs = SystemClock.elapsedRealtimeNanos()
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface-input encoders never expose input buffers.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== this@H264Streamer.codec) {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                return
            }
            try {
                val buffer = codec.getOutputBuffer(index) ?: return
                if (info.size <= 0) return
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buffer.get(bytes)
                val normalized = annexB(bytes)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val isPartial = info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0

                if (isConfig) {
                    codecConfig = normalized
                    broadcast(Ocb2.record(Ocb2.TYPE_CODEC_CONFIG, Ocb2.FLAG_CODEC_CONFIG,
                        currentSequence(), info.presentationTimeUs * 1000L, info.presentationTimeUs, normalized))
                } else {
                    val accumulator = partialAccessUnit
                    if (isPartial || accumulator != null) {
                        val out = accumulator ?: ByteArrayOutputStream(normalized.size * 2).also {
                            partialAccessUnit = it
                            partialPresentationUs = info.presentationTimeUs
                        }
                        partialKeyframe = partialKeyframe || isKey
                        out.write(normalized)
                        if (!isPartial) {
                            partialAccessUnit = null
                            publishAccessUnit(out.toByteArray(), partialPresentationUs, partialKeyframe)
                            partialKeyframe = false
                            partialPresentationUs = 0L
                        }
                    } else {
                        publishAccessUnit(normalized, info.presentationTimeUs, isKey)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) publishError("Encoder output failure: ${e.message}")
            } finally {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codec !== this@H264Streamer.codec) return
            val pieces = listOf("csd-0", "csd-1").mapNotNull { key ->
                if (!format.containsKey(key)) null else format.getByteBuffer(key)?.toByteArray()
            }
            if (pieces.isNotEmpty()) {
                codecConfig = pieces.fold(ByteArray(0)) { all, part -> all + annexB(part) }
                codecConfig?.let { config ->
                    broadcast(Ocb2.record(Ocb2.TYPE_CODEC_CONFIG, Ocb2.FLAG_CODEC_CONFIG,
                        currentSequence(), SystemClock.elapsedRealtimeNanos(), 0, config))
                }
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            // Ignore errors queued by a codec generation that has already been
            // stopped. A real startup error still belongs to the current codec
            // even before running becomes true and must be reported.
            if (codec !== this@H264Streamer.codec) return
            publishError("Hardware encoder ${error.diagnosticInfo}")
            StreamState.fallbackReason.set("Hardware encoder failed: ${error.diagnosticInfo}")
            StreamState.h264Failed.set(true)
        }
    }

    private fun publishAccessUnit(data: ByteArray, presentationTimeUs: Long, keyframe: Boolean) {
        // Several Qualcomm encoders honor PREPEND_HEADER_TO_SYNC_FRAMES but do
        // not emit BUFFER_FLAG_CODEC_CONFIG or expose csd-* in a format-change
        // callback. Derive SPS/PPS from that first IDR so every current and
        // future OCB2 client still receives an explicit type-2 config record.
        if (keyframe && (codecConfig == null || codecConfig?.isEmpty() == true)) {
            extractAnnexBCodecConfig(data)?.let { config ->
                codecConfig = config
                broadcast(Ocb2.record(Ocb2.TYPE_CODEC_CONFIG, Ocb2.FLAG_CODEC_CONFIG,
                    currentSequence(), presentationTimeUs * 1000L, presentationTimeUs, config))
            }
        }
        val flags = if (keyframe) Ocb2.FLAG_KEYFRAME else 0
        val captureNs = max(0L, presentationTimeUs * 1000L)
        broadcast(Ocb2.record(Ocb2.TYPE_VIDEO_ACCESS_UNIT, flags, nextFrameSequence(), captureNs, presentationTimeUs, data))
        val now = SystemClock.elapsedRealtimeNanos()
        encodedWindowFrames++
        encodedWindowBytes += data.size
        if (now - encodedWindowStartNs >= 1_000_000_000L) {
            StreamState.encodedFps.set(encodedWindowFrames)
            StreamState.encodedBitrate.set((encodedWindowBytes * 8L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            encodedWindowFrames = 0
            encodedWindowBytes = 0
            encodedWindowStartNs = now
        }
    }

    private fun publishError(message: String) {
        AppLogger.e("H264", message)
        broadcast(Ocb2.record(Ocb2.TYPE_ERROR, 0, currentSequence(), SystemClock.elapsedRealtimeNanos(), 0,
            message.toByteArray(Charsets.UTF_8)))
    }

    private fun broadcast(record: ByteArray) {
        StreamState.bytesSentThisSecond.addAndGet(record.size.toLong())
        for (client in clients) {
            val result = client.trySend(record)
            if (result.isFailure) {
                clients.remove(client)
                client.close()
            }
        }
        StreamState.h264ClientCount.set(clients.size)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(id: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive) continuation.resume(camera) else camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (continuation.isActive) continuation.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED))
                    else {
                        publishError("Camera disconnected")
                        StreamState.fallbackReason.set("Camera2 device disconnected")
                        StreamState.h264Failed.set(true)
                    }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val failure = IllegalStateException("Camera2 open error $error")
                    if (continuation.isActive) continuation.resumeWithException(failure) else {
                        publishError(failure.message!!)
                        StreamState.fallbackReason.set(failure.message!!)
                        StreamState.h264Failed.set(true)
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    private suspend fun createSession(device: CameraDevice, chosen: H264EncoderSelection) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val encodeSurface = encoderSurface ?: return@suspendCancellableCoroutine continuation.resumeWithException(
                IllegalStateException("Encoder surface was not created"))
            val targets = mutableListOf(encodeSurface)
            val preview = StreamState.camera2PreviewSurface.get()
            if (StreamState.localPreviewEnabled.get() && preview?.isValid == true) targets.add(preview)

            val executor = Executor { command -> cameraHandler?.post(command) }
            fun configure(activeTargets: List<Surface>, mayRetryWithoutPreview: Boolean) {
              try {
                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (!continuation.isActive) { configured.close(); return }
                        session = configured
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        activeTargets.forEach(builder::addTarget)
                        configureRequest(builder, chosen)
                        requestBuilder = builder
                        configured.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                        continuation.resume(Unit)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (!continuation.isActive) return
                        if (mayRetryWithoutPreview) {
                            StreamState.fallbackReason.set("Phone preview surface was incompatible with this H.264 profile; streaming continues without preview")
                            configure(listOf(encodeSurface), false)
                        } else {
                            continuation.resumeWithException(IllegalStateException("Camera2 session configuration failed"))
                        }
                    }
                }
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    activeTargets.map(::OutputConfiguration),
                    executor,
                    callback
                )
                device.createCaptureSession(configuration)
              } catch (e: Exception) {
                  if (continuation.isActive) {
                      if (mayRetryWithoutPreview) configure(listOf(encodeSurface), false)
                      else continuation.resumeWithException(e)
                  }
              }
            }
            configure(targets, targets.size > 1)
        }

    private fun configureRequest(builder: CaptureRequest.Builder, chosen: H264EncoderSelection) {
        val chars = manager.getCameraCharacteristics(StreamState.cameraId.get())
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val range = ranges.filter { it.lower <= chosen.mode.fps && it.upper >= chosen.mode.fps }
            .minWithOrNull(compareBy<Range<Int>>({ it.upper - it.lower }, { -it.lower }))
        if (range != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        applyControls(builder, chars)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: SystemClock.elapsedRealtimeNanos()
            if (captureWindowStartNs == 0L) captureWindowStartNs = timestamp
            captureWindowFrames++
            if (timestamp - captureWindowStartNs >= 1_000_000_000L) {
                StreamState.actualFps.set(captureWindowFrames)
                StreamState.captureFps.set(captureWindowFrames)
                captureWindowFrames = 0
                captureWindowStartNs = timestamp
            }
        }
    }

    private fun applyControls(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, StreamState.zoomRatio.get().coerceIn(range.lower, range.upper))
        } else {
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            if (active != null) builder.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(active, StreamState.zoomRatio.get().coerceIn(1f, maxZoom)))
        }
        builder.set(CaptureRequest.FLASH_MODE, if (StreamState.torchRequested.get()) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    private fun refreshRequest() {
        val builder = requestBuilder ?: return
        val currentSession = session ?: return
        try {
            applyControls(builder, manager.getCameraCharacteristics(StreamState.cameraId.get()))
            currentSession.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (e: Exception) { Log.w(TAG, "Camera control update failed", e) }
    }

    fun setZoomRatio(ratio: Float) {
        StreamState.zoomRatio.set(ratio)
        refreshRequest()
    }

    fun setLinearZoom(linear: Float) {
        val chars = try { manager.getCameraCharacteristics(StreamState.cameraId.get()) } catch (_: Exception) { return }
        val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val maxZoom = range?.upper ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val minZoom = range?.lower ?: 1f
        val target = minZoom + linear.coerceIn(0f, 1f) * (maxZoom - minZoom)
        StreamState.linearZoom.set(linear.coerceIn(0f, 1f))
        zoomJob?.cancel()
        zoomJob = scope.launch {
            var current = StreamState.zoomRatio.get()
            while (kotlin.math.abs(current - target) > 0.03f) {
                current += if (current < target) 0.03f else -0.03f
                setZoomRatio(current)
                delay(30)
            }
            setZoomRatio(target)
        }
    }

    fun setTorch(enabled: Boolean) {
        StreamState.torchRequested.set(enabled)
        StreamState.torchEnabled.set(enabled)
        refreshRequest()
    }

    /** Select the next lower complete camera+encoder profile without changing
     * the user's requested settings. The service restarts this streamer after
     * this returns a mode. */
    fun prepareAdaptiveDowngrade(): H264ModeDto? {
        val current = selection?.mode ?: return null
        val modes = H264Capabilities.supportedModes(context, StreamState.cameraId.get())
        val index = modes.indexOfFirst { it == current }
        if (index < 0 || index + 1 >= modes.size) return null
        return modes[index + 1].also { adaptiveMode = it }
    }

    fun resetAdaptiveProfile() { adaptiveMode = null }

    private fun publishSelection(chosen: H264EncoderSelection) {
        val m = chosen.mode
        StreamState.encoderName.set(chosen.codecName)
        StreamState.hardwareEncoder.set(chosen.hardware)
        StreamState.frameWidth.set(m.width); StreamState.frameHeight.set(m.height)
        StreamState.encodedWidth.set(m.width); StreamState.encodedHeight.set(m.height)
        StreamState.selectedFps.set(m.fps)
        StreamState.selectedRawWidth.set(m.width); StreamState.selectedRawHeight.set(m.height)
        StreamState.selectedEffectiveWidth.set(m.width); StreamState.selectedEffectiveHeight.set(m.height)
        StreamState.fallbackUsed.set(m.width != StreamState.width.get() || m.height != StreamState.height.get() || m.fps != StreamState.fps.get())
        StreamState.fallbackReason.set(if (StreamState.fallbackUsed.get()) "Requested profile unsupported; selected ${m.width}x${m.height}@${m.fps}" else "")
        val payload = JSONObject().apply {
            put("codec", "H264")
            put("framing", "annex-b-access-units")
            put("width", m.width); put("height", m.height)
            put("fpsNumerator", m.fps); put("fpsDenominator", 1)
            put("bitrate", StreamState.actualBitrate.get())
            put("cameraId", StreamState.cameraId.get())
            put("encoderName", chosen.codecName)
            put("hardwareEncoder", chosen.hardware)
            put("pixelFormat", "NV12")
        }.toString().toByteArray(Charsets.UTF_8)
        streamInfo = Ocb2.record(Ocb2.TYPE_STREAM_INFO, Ocb2.FLAG_DISCONTINUITY, currentSequence(), SystemClock.elapsedRealtimeNanos(), 0, payload)
    }

    private fun boundedBitrate(requested: Int, chosen: H264EncoderSelection): Int {
        val practicalMax = when {
            chosen.mode.width >= 1920 && chosen.mode.fps >= 60 -> 20_000_000
            chosen.mode.width >= 1920 -> 14_000_000
            chosen.mode.fps >= 60 -> 12_000_000
            else -> 8_000_000
        }
        return min(practicalMax, chosen.bitrateRange.clamp(max(1_000_000, requested)))
    }

    private fun nextFrameSequence() = frameSequence.incrementAndGet()
    private fun currentSequence() = frameSequence.get()

    private fun annexB(input: ByteArray): ByteArray {
        if (input.size >= 4 && input[0] == 0.toByte() && input[1] == 0.toByte() &&
            (input[2] == 1.toByte() || (input[2] == 0.toByte() && input[3] == 1.toByte()))) return input
        // Some encoders return AVCC length-prefixed NALs. Normalize once at the
        // source so every Windows decoder receives the same Annex-B contract.
        val out = ByteArrayOutputStream(input.size + 16)
        var offset = 0
        while (offset + 4 <= input.size) {
            val len = ((input[offset].toInt() and 0xff) shl 24) or
                ((input[offset + 1].toInt() and 0xff) shl 16) or
                ((input[offset + 2].toInt() and 0xff) shl 8) or (input[offset + 3].toInt() and 0xff)
            offset += 4
            if (len <= 0 || offset + len > input.size) return input
            out.write(byteArrayOf(0, 0, 0, 1)); out.write(input, offset, len); offset += len
        }
        return if (offset == input.size && out.size() > 0) out.toByteArray() else input
    }

    private fun extractAnnexBCodecConfig(accessUnit: ByteArray): ByteArray? {
        data class Nal(val start: Int, val payload: Int, val end: Int)
        val starts = ArrayList<Pair<Int, Int>>(8)
        var i = 0
        while (i + 3 < accessUnit.size) {
            val prefix = when {
                accessUnit[i] == 0.toByte() && accessUnit[i + 1] == 0.toByte() && accessUnit[i + 2] == 1.toByte() -> 3
                i + 4 <= accessUnit.size && accessUnit[i] == 0.toByte() && accessUnit[i + 1] == 0.toByte() &&
                    accessUnit[i + 2] == 0.toByte() && accessUnit[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) {
                starts.add(i to prefix)
                i += prefix
            } else i++
        }
        if (starts.isEmpty()) return null
        val nals = starts.mapIndexedNotNull { index, (start, prefix) ->
            val payload = start + prefix
            val end = if (index + 1 < starts.size) starts[index + 1].first else accessUnit.size
            if (payload < end) Nal(start, payload, end) else null
        }
        val configNals = nals.filter { nal ->
            val type = accessUnit[nal.payload].toInt() and 0x1f
            type == 7 || type == 8
        }
        if (configNals.none { (accessUnit[it.payload].toInt() and 0x1f) == 7 } ||
            configNals.none { (accessUnit[it.payload].toInt() and 0x1f) == 8 }) return null
        val out = ByteArrayOutputStream(configNals.sumOf { it.end - it.start })
        configNals.forEach { out.write(accessUnit, it.start, it.end - it.start) }
        return out.toByteArray()
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun cropForZoom(active: Rect, zoom: Float): Rect {
        val w = (active.width() / zoom).toInt()
        val h = (active.height() / zoom).toInt()
        val left = active.centerX() - w / 2
        val top = active.centerY() - h / 2
        return Rect(left, top, left + w, top + h)
    }

    companion object { private const val TAG = "H264Streamer" }
}
