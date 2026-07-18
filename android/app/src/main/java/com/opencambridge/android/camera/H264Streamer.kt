package com.opencambridge.android.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.opencambridge.android.protocol.Ocb2
import com.opencambridge.android.state.AppLogger
import com.opencambridge.android.state.StreamState
import com.opencambridge.android.state.StreamConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val captureGeneration = AtomicLong(0)
    private val running = AtomicBoolean(false)

    private var cameraThread: HandlerThread? = null
    private var codecThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var codecHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraClosedSignal: CompletableDeferred<Unit>? = null
    private var sessionClosedSignal: CompletableDeferred<Unit>? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    @Volatile private var codec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var gpuBridge: HighSpeedGpuBridge? = null
    private var selection: H264EncoderSelection? = null
    private var activeConfig: StreamConfig? = null
    @Volatile private var activePipelineGeneration: Long = -1
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
    private var bridgeWindowStartNs = 0L
    private var bridgeWindowFrames = 0

    suspend fun start() = lifecycleMutex.withLock {
        stopInternal(sendEnd = false)
        StreamState.rebindInProgress.set(true)
        try {
            val config = StreamState.currentConfig()
            activeConfig = config
            activePipelineGeneration = StreamState.pipelineGeneration.get()
            val cameraId = config.cameraId
            val desiredMode = H264ModeDto(config.width, config.height, config.fps)
            val requested = adaptiveMode
            val candidateModes = when {
                config.profile != "adaptive" -> listOf(desiredMode)
                requested != null -> CapturePathPolicy.adaptiveSuffix(
                    desiredMode, requested, H264Capabilities.preferredModes
                )
                else -> CapturePathPolicy.adaptiveModes(desiredMode, H264Capabilities.preferredModes)
            }
            val candidates = H264Capabilities.selectCandidates(context, cameraId, candidateModes)
            if (candidates.isEmpty()) {
                val declared = candidateModes.joinToString(" | ") { mode ->
                    val paths = H264Capabilities.inspectPathCapabilities(context, cameraId, mode)
                        .joinToString("; ") { "${it.engine}=${it.reason}" }
                    "${mode.width}x${mode.height}@${mode.fps}: $paths"
                }
                throw IllegalStateException("No declared hardware H.264 Camera2 path for camera $cameraId: $declared")
            }

            cameraThread = HandlerThread("OCB2-Camera2").apply { start() }
            codecThread = HandlerThread("OCB2-MediaCodec").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
            codecHandler = Handler(codecThread!!.looper)

            val failures = mutableListOf<String>()
            var chosen: H264EncoderSelection? = null
            for (candidate in candidates) {
                try {
                    captureWindowStartNs = 0L
                    captureWindowFrames = 0
                    bridgeWindowStartNs = 0L
                    bridgeWindowFrames = 0
                    configureCodec(candidate)
                    val candidateGeneration = captureGeneration.incrementAndGet()
                    val device = openCamera(cameraId, candidateGeneration)
                    camera = device
                    createSession(device, candidate, candidateGeneration)
                    chosen = candidate
                    break
                } catch (e: Exception) {
                    val failure = "${candidate.mode.width}x${candidate.mode.height}@${candidate.mode.fps} ${candidate.captureEngine}: ${e.javaClass.simpleName}: ${e.message}"
                    failures += failure
                    AppLogger.w("H264Path", failure)
                    releaseCaptureAttempt()
                }
            }
            val active = chosen ?: throw IllegalStateException(
                "All declared Camera2 H.264 paths were rejected: ${failures.joinToString(" | ")}"
            )
            selection = active
            publishSelection(active, failures)
            running.set(true)
            StreamState.activeStreamMode.set("h264")
            StreamState.h264Failed.set(false)
            heartbeatJob = scope.launch {
                while (running.get()) {
                    delay(1_000)
                    if (running.get()) broadcast(Ocb2.record(Ocb2.TYPE_HEARTBEAT, 0, currentSequence(), SystemClock.elapsedRealtimeNanos(), 0))
                }
            }
            AppLogger.i(
                "H264",
                "${active.codecName}: ${active.mode.width}x${active.mode.height}@${active.mode.fps}, " +
                    "engine=${active.captureEngine}, cameraFps=${active.cameraCaptureFps}, Camera2 surface input"
            )
        } catch (e: Exception) {
            stopInternal(sendEnd = false)
            StreamState.fallbackReason.set("H.264 startup failed: ${e.message}")
            throw e
        } finally {
            StreamState.rebindInProgress.set(false)
        }
    }

    suspend fun stop() = lifecycleMutex.withLock { stopInternal(sendEnd = true) }

    private suspend fun stopInternal(sendEnd: Boolean) {
        val wasRunning = running.getAndSet(false)
        if (sendEnd && wasRunning) broadcast(
            Ocb2.record(Ocb2.TYPE_END_OF_STREAM, Ocb2.FLAG_END_OF_STREAM, currentSequence(), SystemClock.elapsedRealtimeNanos(), 0)
        )
        selection = null
        activeConfig = null
        activePipelineGeneration = -1
        releaseCaptureAttempt()
        codecConfig = null
        streamInfo = null
        partialAccessUnit = null
        partialKeyframe = false
        partialPresentationUs = 0L
        heartbeatJob?.cancel(); heartbeatJob = null
        zoomJob?.cancel(); zoomJob = null
        val oldCameraThread = cameraThread
        val oldCodecThread = codecThread
        oldCameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
        oldCodecThread?.quitSafely(); codecThread = null; codecHandler = null
        if (oldCameraThread != null && oldCameraThread !== Thread.currentThread()) oldCameraThread.join(1_500)
        if (oldCodecThread != null && oldCodecThread !== Thread.currentThread()) oldCodecThread.join(1_500)
        clients.forEach { it.close() }
        clients.clear()
        StreamState.h264ClientCount.set(0)
    }

    private suspend fun releaseCaptureAttempt() {
        captureGeneration.incrementAndGet()
        val oldSession = session
        val oldSessionClosed = sessionClosedSignal
        session = null
        sessionClosedSignal = null
        try { oldSession?.stopRepeating() } catch (_: Exception) {}
        try { oldSession?.abortCaptures() } catch (_: Exception) {}
        oldSession?.close()
        if (oldSession != null && oldSessionClosed != null) {
            if (withTimeoutOrNull(1_500) { oldSessionClosed.await() } == null) {
                AppLogger.w("H264", "Timed out awaiting CameraCaptureSession.onClosed")
            }
        }
        val oldCamera = camera
        val oldCameraClosed = cameraClosedSignal
        camera = null
        cameraClosedSignal = null
        oldCamera?.close()
        if (oldCamera != null && oldCameraClosed != null) {
            if (withTimeoutOrNull(1_500) { oldCameraClosed.await() } == null) {
                AppLogger.w("H264", "Timed out awaiting CameraDevice.onClosed")
            }
        }
        requestBuilder = null
        gpuBridge?.stop()
        gpuBridge = null
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
        // Raise the requested rate to a resolution/fps-appropriate target: the
        // stored default is a flat 4 Mbps that starves 1080p (and especially
        // 1080p60), which read as heavy blockiness. An explicitly higher user
        // request is still honored; boundedBitrate keeps it under the cap.
        val requested = activeConfig?.h264Bitrate ?: StreamState.h264Bitrate.get()
        val bitrate = boundedBitrate(max(requested, recommendedBitrate(chosen.mode)), chosen)
        val highProfileLevel = highProfileLevelFor(chosen.codecName)

        fun buildFormat(withHighProfile: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, chosen.mode.width, chosen.mode.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, chosen.mode.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, activeConfig?.h264KeyframeInterval ?: 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (withHighProfile) {
                    // Constant bitrate keeps quality/latency stable for a live
                    // virtual camera; High profile improves quality-per-bit.
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    if (highProfileLevel != null) {
                        setInteger(MediaFormat.KEY_PROFILE, highProfileLevel.first)
                        setInteger(MediaFormat.KEY_LEVEL, highProfileLevel.second)
                    }
                }
            }

        var c = MediaCodec.createByCodecName(chosen.codecName)
        c.setCallback(codecCallback, codecHandler)
        try {
            c.configure(buildFormat(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            // Some encoders reject explicit High profile/level or CBR. Fall back
            // to the minimal (still higher-bitrate) format so streaming never
            // breaks; quality degrades gracefully instead of failing.
            try { c.release() } catch (_: Exception) {}
            c = MediaCodec.createByCodecName(chosen.codecName)
            c.setCallback(codecCallback, codecHandler)
            c.configure(buildFormat(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
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

    /** Quality-appropriate bitrate floor scaled by resolution and frame rate. */
    private fun recommendedBitrate(mode: H264ModeDto): Int {
        val pixels = mode.width.toLong() * mode.height.toLong()
        return when {
            pixels >= 1920L * 1080L -> if (mode.fps >= 50) 16_000_000 else 10_000_000
            pixels >= 1280L * 720L -> if (mode.fps >= 50) 9_000_000 else 6_000_000
            else -> 3_000_000
        }
    }

    /** Highest AVC High-profile level the named encoder advertises, or null. */
    private fun highProfileLevelFor(codecName: String): Pair<Int, Int>? {
        return try {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.name == codecName } ?: return null
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val best = caps.profileLevels
                .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
                .maxByOrNull { it.level } ?: return null
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh to best.level
        } catch (_: Exception) {
            null
        }
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
            selection?.mode?.let { mode ->
                StreamState.publishActualPipeline(
                    activePipelineGeneration, mode.width, mode.height, StreamState.captureFps.get(),
                    encodedWindowFrames, StreamState.encodedBitrate.get()
                )
            }
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
    private suspend fun openCamera(id: String, generation: Long): CameraDevice = suspendCancellableCoroutine { continuation ->
        val closedSignal = CompletableDeferred<Unit>()
        cameraClosedSignal = closedSignal
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (generation != captureGeneration.get()) {
                        camera.close()
                    } else if (continuation.isActive) {
                        continuation.resume(camera)
                    } else {
                        camera.close()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (continuation.isActive) continuation.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED))
                    else if (generation == captureGeneration.get() && camera === this@H264Streamer.camera) {
                        publishError("Camera disconnected")
                        StreamState.fallbackReason.set("Camera2 device disconnected")
                        StreamState.h264Failed.set(true)
                    }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val failure = IllegalStateException("Camera2 open error $error")
                    if (continuation.isActive) continuation.resumeWithException(failure) else {
                        if (generation == captureGeneration.get() && camera === this@H264Streamer.camera) {
                            publishError(failure.message!!)
                            StreamState.fallbackReason.set(failure.message!!)
                            StreamState.h264Failed.set(true)
                        }
                    }
                }
                override fun onClosed(camera: CameraDevice) {
                    closedSignal.complete(Unit)
                }
            }, cameraHandler)
        } catch (e: Exception) {
            closedSignal.complete(Unit)
            continuation.resumeWithException(e)
        }
    }

    private suspend fun createSession(device: CameraDevice, chosen: H264EncoderSelection, generation: Long) {
        val encodeSurface = encoderSurface ?: throw IllegalStateException("Encoder surface was not created")
        val pipelineGeneration = activePipelineGeneration
        val previewRequested = activeConfig?.localPreviewEnabled == true
        val preview = StreamState.camera2PreviewSurface.get()
            ?.takeIf { previewRequested && it.isValid }
        if (!previewRequested) StreamState.publishPhonePreview(pipelineGeneration, false, "")
        else if (preview == null) StreamState.publishPhonePreview(
            pipelineGeneration,
            false,
            "Phone preview requested but no valid Camera2 Surface target is attached"
        )
        val captureSurface = if (chosen.captureEngine == H264CaptureEngine.HIGH_SPEED_GPU_BRIDGE) {
            HighSpeedGpuBridge(
                encoderSurface = encodeSurface,
                previewSurface = preview,
                width = chosen.mode.width,
                height = chosen.mode.height,
                outputFps = chosen.mode.fps,
                onFrameRendered = ::onBridgeFrameRendered,
                onPreviewState = { active, failure ->
                    StreamState.publishPhonePreview(pipelineGeneration, active, failure)
                },
                onError = { error ->
                    if (generation == captureGeneration.get()) {
                        publishError(error)
                        StreamState.capturePathError.set(error)
                        StreamState.fallbackReason.set(error)
                        StreamState.h264Failed.set(true)
                    }
                }
            ).also { gpuBridge = it }.start()
        } else {
            encodeSurface
        }
        val targets = mutableListOf(captureSurface)
        if (chosen.captureEngine != H264CaptureEngine.HIGH_SPEED_GPU_BRIDGE && preview != null) {
            targets.add(preview)
        }
        configureCameraSession(
            device, chosen, targets, captureSurface, targets.size > 1, generation, pipelineGeneration
        )
    }

    private suspend fun configureCameraSession(
        device: CameraDevice,
        chosen: H264EncoderSelection,
        initialTargets: List<Surface>,
        requiredSurface: Surface,
        mayRetryWithoutPreview: Boolean,
        generation: Long,
        pipelineGeneration: Long
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val executor = Executor { command -> cameraHandler?.post(command) }
        fun configure(activeTargets: List<Surface>, mayRetry: Boolean) {
            try {
                val closedSignal = CompletableDeferred<Unit>()
                sessionClosedSignal = closedSignal
                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (generation != captureGeneration.get()) {
                            configured.close()
                            if (continuation.isActive) continuation.resumeWithException(
                                IllegalStateException("${chosen.captureEngine} callback belonged to a stale generation")
                            )
                            return
                        }
                        if (!continuation.isActive) {
                            configured.close()
                            return
                        }
                        try {
                            session = configured
                            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            activeTargets.forEach(builder::addTarget)
                            configureRequest(builder, chosen)
                            requestBuilder = builder
                            submitRepeating(configured, builder.build(), chosen)
                            if (chosen.captureEngine != H264CaptureEngine.HIGH_SPEED_GPU_BRIDGE) {
                                val previewActive = activeTargets.size > 1
                                StreamState.publishPhonePreview(
                                    pipelineGeneration,
                                    previewActive,
                                    if (!previewActive && activeConfig?.localPreviewEnabled == true) {
                                        "${chosen.captureEngine} is running encoder-only after preview rejection"
                                    } else ""
                                )
                            }
                            continuation.resume(Unit)
                        } catch (e: Exception) {
                            configured.close()
                            session = null
                            if (continuation.isActive) continuation.resumeWithException(
                                IllegalStateException(
                                    "${chosen.captureEngine} repeating request rejected: ${e.javaClass.simpleName}: ${e.message}",
                                    e
                                )
                            )
                        }
                    }

                    override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                        failedSession.close()
                        if (!continuation.isActive) return
                        if (mayRetry) {
                            val reason = "${chosen.captureEngine} rejected encoder+preview session; retrying encoder-only"
                            AppLogger.w(
                                "H264Path",
                                reason
                            )
                            StreamState.publishPhonePreview(pipelineGeneration, false, reason)
                            configure(listOf(requiredSurface), false)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException("${chosen.captureEngine} session onConfigureFailed")
                            )
                        }
                    }

                    override fun onClosed(closedSession: CameraCaptureSession) {
                        closedSignal.complete(Unit)
                    }
                }
                val sessionType = if (chosen.captureEngine == H264CaptureEngine.REGULAR_SURFACE) {
                    SessionConfiguration.SESSION_REGULAR
                } else {
                    SessionConfiguration.SESSION_HIGH_SPEED
                }
                val configuration = SessionConfiguration(
                    sessionType,
                    activeTargets.map(::OutputConfiguration),
                    executor,
                    callback
                )
                device.createCaptureSession(configuration)
            } catch (e: Exception) {
                if (!continuation.isActive) return
                if (mayRetry) {
                    StreamState.publishPhonePreview(
                        pipelineGeneration,
                        false,
                        "${chosen.captureEngine} encoder+preview createCaptureSession rejected: " +
                            "${e.javaClass.simpleName}: ${e.message}; retrying encoder-only"
                    )
                    configure(listOf(requiredSurface), false)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException(
                            "${chosen.captureEngine} createCaptureSession rejected: ${e.javaClass.simpleName}: ${e.message}",
                            e
                        )
                    )
                }
            }
        }
        continuation.invokeOnCancellation { session?.close() }
        configure(initialTargets, mayRetryWithoutPreview)
    }

    private fun configureRequest(builder: CaptureRequest.Builder, chosen: H264EncoderSelection) {
        val chars = manager.getCameraCharacteristics(activeConfig?.cameraId ?: StreamState.cameraId.get())
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen.cameraFpsRange)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        applyControls(builder, chars)
    }

    private fun submitRepeating(
        activeSession: CameraCaptureSession,
        request: CaptureRequest,
        chosen: H264EncoderSelection
    ) {
        if (chosen.captureEngine == H264CaptureEngine.REGULAR_SURFACE) {
            activeSession.setRepeatingRequest(request, captureCallback, cameraHandler)
            return
        }
        val highSpeed = activeSession as? CameraConstrainedHighSpeedCaptureSession
            ?: throw IllegalStateException(
                "${chosen.captureEngine} configured a non-constrained session: ${activeSession.javaClass.name}"
            )
        val burst = highSpeed.createHighSpeedRequestList(request)
        highSpeed.setRepeatingBurst(burst, captureCallback, cameraHandler)
    }

    private fun onBridgeFrameRendered(timestampNs: Long) {
        if (bridgeWindowStartNs == 0L) bridgeWindowStartNs = timestampNs
        bridgeWindowFrames++
        if (timestampNs - bridgeWindowStartNs >= 1_000_000_000L) {
            StreamState.gpuBridgeFps.set(bridgeWindowFrames)
            bridgeWindowFrames = 0
            bridgeWindowStartNs = timestampNs
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (session !== this@H264Streamer.session) return
            val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: SystemClock.elapsedRealtimeNanos()
            if (captureWindowStartNs == 0L) captureWindowStartNs = timestamp
            captureWindowFrames++
            if (timestamp - captureWindowStartNs >= 1_000_000_000L) {
                StreamState.actualFps.set(captureWindowFrames)
                StreamState.captureFps.set(captureWindowFrames)
                StreamState.cameraSessionFps.set(captureWindowFrames)
                selection?.mode?.let { mode ->
                    StreamState.publishActualPipeline(
                        activePipelineGeneration, mode.width, mode.height, captureWindowFrames,
                        StreamState.encodedFps.get(), StreamState.encodedBitrate.get()
                    )
                }
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
        val chosen = selection ?: return
        try {
            applyControls(builder, manager.getCameraCharacteristics(activeConfig?.cameraId ?: StreamState.cameraId.get()))
            submitRepeating(currentSession, builder.build(), chosen)
        } catch (e: Exception) { Log.w(TAG, "Camera control update failed", e) }
    }

    fun setZoomRatio(ratio: Float) {
        StreamState.zoomRatio.set(ratio)
        refreshRequest()
    }

    fun setLinearZoom(linear: Float) {
        val chars = try { manager.getCameraCharacteristics(activeConfig?.cameraId ?: StreamState.cameraId.get()) } catch (_: Exception) { return }
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
        val config = activeConfig ?: return null
        if (config.profile != "adaptive") return null
        val current = selection?.mode ?: return null
        val supported = H264Capabilities.supportedModes(context, config.cameraId)
        val requested = H264ModeDto(config.width, config.height, config.fps)
        val modes = CapturePathPolicy.adaptiveModes(requested, H264Capabilities.preferredModes)
            .filter(supported::contains)
        val index = modes.indexOfFirst { it == current }
        if (index < 0 || index + 1 >= modes.size) return null
        return modes[index + 1].also { adaptiveMode = it }
    }

    fun resetAdaptiveProfile() { adaptiveMode = null }

    private fun publishSelection(chosen: H264EncoderSelection, rejectedPaths: List<String>) {
        val m = chosen.mode
        val config = activeConfig ?: StreamState.currentConfig()
        StreamState.encoderName.set(chosen.codecName)
        StreamState.hardwareEncoder.set(chosen.hardware)
        StreamState.captureEngine.set(chosen.captureEngine.name)
        StreamState.cameraSessionFps.set(chosen.cameraCaptureFps)
        StreamState.capturePathError.set(rejectedPaths.joinToString(" | "))
        StreamState.gpuBridgeFps.set(0)
        StreamState.frameWidth.set(m.width); StreamState.frameHeight.set(m.height)
        StreamState.encodedWidth.set(m.width); StreamState.encodedHeight.set(m.height)
        StreamState.selectedFps.set(m.fps)
        StreamState.selectedRawWidth.set(m.width); StreamState.selectedRawHeight.set(m.height)
        StreamState.selectedEffectiveWidth.set(m.width); StreamState.selectedEffectiveHeight.set(m.height)
        val usedFallback = m.width != config.width || m.height != config.height || m.fps != config.fps
        val fallback = when {
            usedFallback -> "Requested profile unsupported or rejected; selected ${m.width}x${m.height}@${m.fps} using ${chosen.captureEngine}"
            rejectedPaths.isNotEmpty() -> "Earlier capture paths rejected; using ${chosen.captureEngine}: ${rejectedPaths.joinToString(" | ")}"
            else -> ""
        }
        StreamState.publishFallback(usedFallback || rejectedPaths.isNotEmpty(), fallback)
        StreamState.publishSelectedPipeline(
            activePipelineGeneration, config.cameraId, "h264", chosen.captureEngine.name, m.width, m.height,
            m.fps, chosen.codecName, chosen.hardware
        )
        streamInfo = buildStreamInfo(chosen, config, rejectedPaths)
        lastRejectedPaths = rejectedPaths
    }

    private var lastRejectedPaths: List<String> = emptyList()

    /**
     * Build the OCB2 stream-info record (codec + geometry + rotation metadata)
     * and publish the derived rotation into StreamState. Rotation is metadata
     * only for H.264 (the Windows producer applies it), so this can be rebuilt
     * mid-stream to update orientation without touching the codec or session.
     */
    private fun buildStreamInfo(
        chosen: H264EncoderSelection,
        config: StreamConfig,
        rejectedPaths: List<String>
    ): ByteArray {
        val m = chosen.mode
        val chars = manager.getCameraCharacteristics(config.cameraId)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceRotation = FrameTransformPolicy.surfaceRotationDegrees(StreamState.deviceSurfaceRotation.get())
        val transform = FrameTransformPolicy.calculate(
            sensorOrientation,
            deviceRotation,
            chars.get(CameraCharacteristics.LENS_FACING),
            config.displayRotation,
            config.mirror
        )
        StreamState.sensorOrientation.set(transform.sensorOrientation)
        StreamState.rotationDegrees.set(transform.effectiveRotation)
        val payload = JSONObject().apply {
            put("codec", "H264")
            put("framing", "annex-b-access-units")
            put("width", m.width); put("height", m.height)
            put("fpsNumerator", m.fps); put("fpsDenominator", 1)
            put("bitrate", StreamState.actualBitrate.get())
            put("cameraId", config.cameraId)
            put("encoderName", chosen.codecName)
            put("hardwareEncoder", chosen.hardware)
            put("captureEngine", chosen.captureEngine.name)
            put("cameraCaptureFps", chosen.cameraCaptureFps)
            put("effectiveRotation", transform.effectiveRotation)
            put("mirror", transform.mirror)
            put("sensorOrientation", transform.sensorOrientation)
            put("deviceRotation", transform.deviceRotation)
            put("rejectedCapturePaths", rejectedPaths.joinToString(" | "))
            put("pixelFormat", "NV12")
        }.toString().toByteArray(Charsets.UTF_8)
        return Ocb2.record(Ocb2.TYPE_STREAM_INFO, Ocb2.FLAG_DISCONTINUITY, currentSequence(), SystemClock.elapsedRealtimeNanos(), 0, payload)
    }

    /**
     * Re-emit rotation metadata to connected OCB2 clients after a device
     * orientation change, WITHOUT restarting the camera/encoder. Previously every
     * hand-held tilt across a rotation boundary triggered a full pipeline restart
     * (Recover), which sent end-of-stream and froze the video while the producer
     * reconnected. The producer applies rotation from stream-info, so pushing a
     * fresh stream-info + keyframe keeps the stream live.
     */
    fun onDeviceOrientationChanged() {
        if (!running.get()) return
        val chosen = selection ?: return
        val config = activeConfig ?: return
        val record = try {
            buildStreamInfo(chosen, config, lastRejectedPaths)
        } catch (e: Exception) {
            Log.w(TAG, "Rotation update failed", e); return
        }
        streamInfo = record
        broadcast(record)
        requestKeyFrame()
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
