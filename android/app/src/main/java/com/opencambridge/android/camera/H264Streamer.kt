package com.opencambridge.android.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Encodes CameraX frames to H.264 via MediaCodec.
 * Distributes Annex B stream to active channels.
 * Occasionally generates JPEG for Web UI compatibility.
 */
class H264Streamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val rebindMutex = Mutex()
    private var zoomJob: Job? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCamera: Camera? = null

    private var mediaCodec: MediaCodec? = null
    private var isEncoding = false
    private var encodeJob: Job? = null

    // Dimensions the encoder was configured with. Frames that do not match are
    // dropped instead of being fed to the codec as misinterpreted memory.
    private var codecWidth = 0
    private var codecHeight = 0

    // Raw input layout negotiated with the encoder (NV12 vs I420). Assuming
    // NV12 everywhere corrupts color/geometry on planar-input devices.
    private var codecColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    private var spsPpsBuffer: ByteArray? = null
    private val clients = CopyOnWriteArrayList<Channel<ByteArray>>()

    // Throttling for MJPEG generation
    private var lastJpegTime = 0L
    private val jpegThrottleMs = 200L // 5 FPS for web preview fallback

    // Telemetry
    private var framesReceived = 0L
    private var framesEncoded = 0L

    // Reusable buffers
    private var nv12Buffer: ByteArray? = null
    private var nv21Buffer: ByteArray? = null

    suspend fun start() {
        val provider = suspendCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
        cameraProvider = provider
        // Propagates bind/codec failures to the caller so the service can enter
        // ERROR state instead of pretending to stream.
        bindCameraSafe()
    }

    private suspend fun bindCameraSafe() {
        rebindMutex.withLock {
            StreamState.rebindInProgress.set(true)
            try {
                val provider = cameraProvider ?: return@withLock

                currentCamera?.cameraControl?.enableTorch(false)
                StreamState.torchEnabled.set(false)
                zoomJob?.cancel()

                stopCodec()

                withContext(Dispatchers.Main) {
                    provider.unbindAll()
                }

                val selector = buildSelector(StreamState.cameraId.get())
                val fps = StreamState.fps.get()
                val fpsRange = FpsRanges.choose(context, StreamState.cameraId.get(), fps)

                val resSelector = ResolutionPolicy.buildSelector(
                    profile = StreamState.profile.get(),
                    requestedWidth = StreamState.width.get(),
                    requestedHeight = StreamState.height.get(),
                    allowNative = StreamState.profile.get() == "native",
                    allowAspectFallback = false
                )

                val imageAnalysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(resSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                if (fpsRange != null) {
                    try {
                        androidx.camera.camera2.interop.Camera2Interop.Extender(imageAnalysisBuilder)
                            .setCaptureRequestOption(
                                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                fpsRange
                            )
                    } catch (e: Exception) {
                        Log.w("H264Streamer", "Could not set FPS range $fpsRange", e)
                    }
                }

                val imageAnalysis = imageAnalysisBuilder.build()

                imageAnalysis.setAnalyzer(analysisExecutor, ::processFrame)
                StreamState.imageAnalysisUseCase = imageAnalysis

                val preview = Preview.Builder()
                    .setResolutionSelector(resSelector)
                    .build()
                StreamState.previewUseCase = preview

                val surfaceProvider = StreamState.surfaceProvider

                try {
                    val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalysis)

                    // The encoder is configured with whatever buffer size
                    // CameraX actually selected — feeding it the requested size
                    // when the device picked another one corrupts the stream.
                    var selectedW = StreamState.width.get()
                    var selectedH = StreamState.height.get()

                    withContext(Dispatchers.Main) {
                        if (StreamState.localPreviewEnabled.get() && surfaceProvider != null) {
                            preview.setSurfaceProvider(surfaceProvider)
                            useCases.add(preview)
                        }
                        currentCamera = provider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            *useCases.toTypedArray()
                        )
                        observeCameraControls()

                        val resolution = imageAnalysis.resolutionInfo?.resolution
                        if (resolution != null) {
                            val sensorRot = imageAnalysis.resolutionInfo?.rotationDegrees ?: 0
                            val isRotated = sensorRot % 180 != 0
                            val effW = if (isRotated) resolution.height else resolution.width
                            val effH = if (isRotated) resolution.width else resolution.height

                            StreamState.selectedRawWidth.set(resolution.width)
                            StreamState.selectedRawHeight.set(resolution.height)
                            StreamState.selectedEffectiveWidth.set(effW)
                            StreamState.selectedEffectiveHeight.set(effH)
                            StreamState.normalizedForPolicy.set(true)

                            selectedW = resolution.width
                            selectedH = resolution.height

                            Log.i("H264Streamer", "Selected Resolution: ${resolution.width}x${resolution.height} (Effective: ${effW}x${effH})")
                        }
                    }

                    // Start the encoder only now that the real capture size is
                    // known. Frames delivered before this point are dropped by
                    // processFrame (isEncoding is still false).
                    startCodec(selectedW, selectedH, fps)

                    StreamState.streaming.set(true)
                } catch (e: Exception) {
                    Log.e("H264Streamer", "bind/codec start failed: ${e.message}")
                    throw e
                }
            } finally {
                StreamState.rebindInProgress.set(false)
            }
        }
    }

    suspend fun stop() {
        rebindMutex.withLock {
            withContext(Dispatchers.Main) {
                cameraProvider?.unbindAll()
            }
            currentCamera = null
            stopCodec()
            StreamState.streaming.set(false)
            StreamState.latestFrame.set(null)
            clients.forEach { it.close() }
            clients.clear()
            StreamState.h264ClientCount.set(0)
        }
    }

    // ---- Channels ----

    fun subscribe(): Channel<ByteArray> {
        // Bounded buffer: an H.264 stream must not be silently thinned (dropping
        // arbitrary NAL units corrupts the bitstream until the next IDR), so instead
        // of dropping we disconnect clients that fall too far behind (see drainCodec).
        val channel = Channel<ByteArray>(capacity = 512)
        clients.add(channel)
        StreamState.h264ClientCount.set(clients.size)

        // Send SPS/PPS immediately if available
        val sps = spsPpsBuffer
        if (sps != null) {
            channel.trySend(sps)
        }
        return channel
    }

    fun unsubscribe(channel: Channel<ByteArray>) {
        clients.remove(channel)
        StreamState.h264ClientCount.set(clients.size)
        channel.close()
    }

    // ---- Codec Management ----

    private fun startCodec(width: Int, height: Int, fps: Int) {
        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            // Negotiate a concrete raw input layout instead of requesting
            // "flexible" and guessing: pick semi-planar (NV12) when supported,
            // planar (I420) otherwise, and convert camera frames accordingly.
            val caps = try {
                mediaCodec?.codecInfo?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (e: Exception) {
                null
            }
            val supportedColors = caps?.colorFormats?.toList() ?: emptyList()
            codecColorFormat = when {
                supportedColors.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                supportedColors.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, codecColorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, StreamState.h264Bitrate.get())
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, StreamState.h264KeyframeInterval.get())

            // CBR keeps streaming bandwidth steady; request it only when the
            // encoder advertises support (some reject it at configure time).
            try {
                if (caps?.encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    ) == true
                ) {
                    format.setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                }
            } catch (e: Exception) {
                // keep the encoder's default rate control
            }

            // Ask the encoder to repeat SPS/PPS before every IDR frame so late
            // joiners and reconnecting decoders can sync mid-stream. Vendor
            // key; encoders that do not know it ignore it.
            format.setInteger("prepend-sps-pps-to-idr-frames", 1)

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            codecWidth = width
            codecHeight = height

            isEncoding = true
            encodeJob = scope.launch {
                drainCodec()
            }
            Log.i(
                "H264Streamer",
                "Started H.264 Codec: ${width}x${height} @ ${fps}fps, ${StreamState.h264Bitrate.get()} bps, colorFormat=$codecColorFormat"
            )
        } catch (e: Exception) {
            Log.e("H264Streamer", "Failed to start MediaCodec", e)
            stopCodec()
            throw e
        }
    }

    private fun stopCodec() {
        isEncoding = false
        encodeJob?.cancel()
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e("H264Streamer", "Error stopping codec", e)
        }
        mediaCodec = null
        spsPpsBuffer = null
    }

    private fun drainCodec() {
        val bufferInfo = MediaCodec.BufferInfo()
        val codec = mediaCodec ?: return

        while (isEncoding) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // format changed
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)

                        if (isConfig) {
                            spsPpsBuffer = data
                        }

                        // Always broadcast. Some hardware packs SPS/PPS inline with the first IDR keyframe!
                        for (client in clients) {
                            val result = client.trySend(data)
                            if (result.isFailure && !result.isClosed) {
                                // Client is too slow to keep up; disconnect it rather than
                                // buffering unbounded memory or corrupting its bitstream.
                                Log.w("H264Streamer", "Dropping slow H.264 client (buffer full)")
                                clients.remove(client)
                                StreamState.h264ClientCount.set(clients.size)
                                client.close()
                            }
                        }

                        framesEncoded++
                        if (framesEncoded % 60L == 0L) {
                            Log.d("H264Streamer", "Telemetry: Received=$framesReceived, Encoded=$framesEncoded, Clients=${clients.size}")
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) {
                if (isEncoding) Log.e("H264Streamer", "Error draining codec", e)
            }
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        // Drop frames if we are in the middle of a rebind to prevent native crashes
        if (StreamState.rebindInProgress.get() || !isEncoding) {
            imageProxy.close()
            return
        }

        val codec = mediaCodec
        if (codec == null) {
            imageProxy.close()
            return
        }

        framesReceived++

        try {
            StreamState.rotationDegrees.set(imageProxy.imageInfo.rotationDegrees)
            StreamState.frameWidth.set(imageProxy.width)
            StreamState.frameHeight.set(imageProxy.height)

            val width = imageProxy.width
            val height = imageProxy.height

            // Never feed the encoder a buffer size it was not configured for.
            if (width != codecWidth || height != codecHeight) {
                Log.w("H264Streamer", "Dropping ${width}x${height} frame; codec expects ${codecWidth}x${codecHeight}")
                return
            }

            // Metrics: what is actually being encoded (also lets the desktop
            // confirm a rebind completed in H.264 mode).
            StreamState.encodedWidth.set(width)
            StreamState.encodedHeight.set(height)

            val actualRatio = width.toFloat() / height
            val aspect16_9 = 16f / 9f
            val aspect4_3 = 4f / 3f

            if (kotlin.math.abs(actualRatio - aspect16_9) < 0.1 || kotlin.math.abs(1f/actualRatio - aspect16_9) < 0.1) {
                StreamState.selectedAspectRatio.set("16:9")
            } else if (kotlin.math.abs(actualRatio - aspect4_3) < 0.1 || kotlin.math.abs(1f/actualRatio - aspect4_3) < 0.1) {
                StreamState.selectedAspectRatio.set("4:3")
            } else {
                StreamState.selectedAspectRatio.set(String.format(java.util.Locale.US, "%.2f", actualRatio))
            }

            val reqAspect = StreamState.requestedAspectRatio.get()
            StreamState.aspectRatioMatch.set(reqAspect.startsWith(StreamState.selectedAspectRatio.get()))

            val targetW = StreamState.width.get()
            val targetH = StreamState.height.get()
            val rotatedW = if (StreamState.rotationDegrees.get() % 180 != 0) height else width
            val rotatedH = if (StreamState.rotationDegrees.get() % 180 != 0) width else height

            StreamState.resizeNeeded.set(rotatedW != targetW || rotatedH != targetH)

            val frameSize = width * height + (width / 2) * (height / 2) * 2

            if (nv12Buffer?.size != frameSize) nv12Buffer = ByteArray(frameSize)
            if (nv21Buffer?.size != frameSize) nv21Buffer = ByteArray(frameSize)

            // Feed frame to the H.264 encoder in its negotiated input layout
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    val raw = nv12Buffer!!
                    if (codecColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                        yuvToI420(imageProxy, raw)
                    } else {
                        yuvToNv12(imageProxy, raw)
                    }
                    inputBuffer.clear()
                    inputBuffer.put(raw)
                    val pts = imageProxy.imageInfo.timestamp / 1000 // Convert nanoseconds to microseconds
                    codec.queueInputBuffer(inputBufferIndex, 0, raw.size, pts, 0)

                    // FPS window metrics (same accounting as the MJPEG path,
                    // so /api/stream/metrics is truthful in H.264 mode too).
                    val nowMs = System.currentTimeMillis()
                    StreamState.framesThisSecond.incrementAndGet()
                    val windowStart = StreamState.fpsWindowStartMs.get()
                    if (nowMs - windowStart >= 1000L) {
                        if (StreamState.fpsWindowStartMs.compareAndSet(windowStart, nowMs)) {
                            val count = StreamState.framesThisSecond.getAndSet(0)
                            StreamState.actualFps.set(count)
                        }
                    }
                }
            }

            // 2. Feed MJPEG fallback if needed
            val now = System.currentTimeMillis()
            if (now - lastJpegTime > jpegThrottleMs) {
                val nv21 = nv21Buffer!!
                yuvToNv21(imageProxy, nv21)

                // Offload heavy compression to avoid dropping the next H.264 frame
                val nv21Copy = nv21.clone()
                val currentQuality = StreamState.jpegQuality.get()
                scope.launch {
                    try {
                        val yuvImage = YuvImage(nv21Copy, ImageFormat.NV21, width, height, null)
                        val out = ByteArrayOutputStream()
                        yuvImage.compressToJpeg(Rect(0, 0, width, height), currentQuality, out)
                        StreamState.latestFrame.set(out.toByteArray())
                    } catch (e: Exception) {
                        Log.e("H264Streamer", "Async JPEG fallback failed", e)
                    }
                }
                lastJpegTime = now
            }
        } catch (e: Exception) {
            Log.e("H264Streamer", "Frame processing failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun yuvToNv12(image: ImageProxy, outBuf: ByteArray) {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val yBuf = yPlane.buffer
        yBuf.rewind()

        var dstOffset = 0
        if (yRowStride == width) {
            val toCopy = kotlin.math.min(width * height, yBuf.remaining())
            yBuf.get(outBuf, 0, toCopy)
            dstOffset = width * height
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                val toCopy = kotlin.math.min(width, yBuf.remaining())
                yBuf.get(outBuf, dstOffset, toCopy)
                dstOffset += width
            }
        }

        val chromaH = height / 2
        val chromaW = width / 2
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        // For NV12, we want U then V.
        if (uvPixelStride == 2 && uvRowStride == width) {
            uBuf.rewind()
            val length = chromaH * chromaW * 2
            val toCopy = kotlin.math.min(length, uBuf.remaining())
            uBuf.get(outBuf, dstOffset, toCopy)
        } else if (uvPixelStride == 2) {
            for (row in 0 until chromaH) {
                val pos = row * uvRowStride
                if (pos < uBuf.limit()) {
                    uBuf.position(pos)
                    val toCopy = kotlin.math.min(width, uBuf.remaining())
                    uBuf.get(outBuf, dstOffset, toCopy)
                }
                dstOffset += width
            }
        } else {
            for (row in 0 until chromaH) {
                var offset = 0
                for (col in 0 until chromaW) {
                    val srcIndex = row * uvRowStride + col * uvPixelStride
                    if (srcIndex < uBuf.limit()) {
                        uBuf.position(srcIndex)
                        outBuf[dstOffset + offset++] = if (uBuf.remaining() > 0) uBuf.get() else 0
                    } else {
                        outBuf[dstOffset + offset++] = 0
                    }
                    if (srcIndex < vBuf.limit()) {
                        vBuf.position(srcIndex)
                        outBuf[dstOffset + offset++] = if (vBuf.remaining() > 0) vBuf.get() else 0
                    } else {
                        outBuf[dstOffset + offset++] = 0
                    }
                }
                dstOffset += width
            }
        }
    }

    /**
     * Converts a YUV_420_888 ImageProxy to planar I420 (all Y, then all U,
     * then all V) for encoders that negotiated COLOR_FormatYUV420Planar.
     */
    private fun yuvToI420(image: ImageProxy, outBuf: ByteArray) {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val yBuf = yPlane.buffer
        yBuf.rewind()

        var dstOffset = 0
        if (yRowStride == width) {
            val toCopy = kotlin.math.min(width * height, yBuf.remaining())
            yBuf.get(outBuf, 0, toCopy)
            dstOffset = width * height
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                val toCopy = kotlin.math.min(width, yBuf.remaining())
                yBuf.get(outBuf, dstOffset, toCopy)
                dstOffset += width
            }
        }

        val chromaH = height / 2
        val chromaW = width / 2

        // U plane then V plane, each downsampled chromaW x chromaH.
        for (plane in listOf(uPlane, vPlane)) {
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buf = plane.buffer
            for (row in 0 until chromaH) {
                if (pixelStride == 1) {
                    val pos = row * rowStride
                    if (pos < buf.limit()) {
                        buf.position(pos)
                        val toCopy = kotlin.math.min(chromaW, buf.remaining())
                        buf.get(outBuf, dstOffset, toCopy)
                    }
                    dstOffset += chromaW
                } else {
                    for (col in 0 until chromaW) {
                        val srcIndex = row * rowStride + col * pixelStride
                        if (srcIndex < buf.limit()) {
                            buf.position(srcIndex)
                            outBuf[dstOffset++] = if (buf.remaining() > 0) buf.get() else 0
                        } else {
                            outBuf[dstOffset++] = 0
                        }
                    }
                }
            }
        }
    }

    private fun yuvToNv21(image: ImageProxy, outBuf: ByteArray) {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val vPlane = image.planes[2]
        val uPlane = image.planes[1]

        val yRowStride = yPlane.rowStride
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride

        val yBuf = yPlane.buffer
        yBuf.rewind()

        var dstOffset = 0
        if (yRowStride == width) {
            val toCopy = kotlin.math.min(width * height, yBuf.remaining())
            yBuf.get(outBuf, 0, toCopy)
            dstOffset = width * height
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                val toCopy = kotlin.math.min(width, yBuf.remaining())
                yBuf.get(outBuf, dstOffset, toCopy)
                dstOffset += width
            }
        }

        val chromaH = height / 2
        val chromaW = width / 2
        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer

        // For NV21, we want V then U.
        if (uvPixelStride == 2 && uvRowStride == width) {
            vBuf.rewind()
            val length = chromaH * chromaW * 2
            val toCopy = kotlin.math.min(length, vBuf.remaining())
            vBuf.get(outBuf, dstOffset, toCopy)
        } else if (uvPixelStride == 2) {
            for (row in 0 until chromaH) {
                val pos = row * uvRowStride
                if (pos < vBuf.limit()) {
                    vBuf.position(pos)
                    val toCopy = kotlin.math.min(width, vBuf.remaining())
                    vBuf.get(outBuf, dstOffset, toCopy)
                }
                dstOffset += width
            }
        } else {
            for (row in 0 until chromaH) {
                var offset = 0
                for (col in 0 until chromaW) {
                    val srcIndex = row * uvRowStride + col * uvPixelStride
                    if (srcIndex < vBuf.limit()) {
                        vBuf.position(srcIndex)
                        outBuf[dstOffset + offset++] = if (vBuf.remaining() > 0) vBuf.get() else 0
                    } else {
                        outBuf[dstOffset + offset++] = 0
                    }
                    if (srcIndex < uBuf.limit()) {
                        uBuf.position(srcIndex)
                        outBuf[dstOffset + offset++] = if (uBuf.remaining() > 0) uBuf.get() else 0
                    } else {
                        outBuf[dstOffset + offset++] = 0
                    }
                }
                dstOffset += width
            }
        }
    }

    private fun buildSelector(cameraId: String): CameraSelector =
        CameraSelectors.forCameraId(cameraId)

    private fun observeCameraControls() {
        val camInfo = currentCamera?.cameraInfo ?: return

        val hasFlash = camInfo.hasFlashUnit()
        StreamState.hasTorch.set(hasFlash)
        if (!hasFlash && StreamState.torchEnabled.get()) {
            StreamState.torchEnabled.set(false)
            currentCamera?.cameraControl?.enableTorch(false)
        }
        // Restore the user's requested torch state after a (re)bind.
        if (hasFlash && StreamState.torchRequested.get()) {
            currentCamera?.cameraControl?.enableTorch(true)
        }

        camInfo.zoomState.observe(lifecycleOwner) { state ->
            StreamState.zoomRatio.set(state.zoomRatio)
            StreamState.linearZoom.set(state.linearZoom)
        }

        camInfo.torchState.observe(lifecycleOwner) { state ->
            StreamState.torchEnabled.set(state == androidx.camera.core.TorchState.ON)
        }
    }

    fun setZoomRatio(ratio: Float) {
        currentCamera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setLinearZoom(linear: Float) {
        val speed = StreamState.zoomSpeed.get()
        val step = when (speed) {
            "slow" -> 0.01f
            "fast" -> 0.1f
            else -> 0.03f
        }
        val delayMs = when (speed) {
            "slow" -> 50L
            "fast" -> 20L
            else -> 30L
        }

        zoomJob?.cancel()
        zoomJob = scope.launch {
            var current = currentCamera?.cameraInfo?.zoomState?.value?.linearZoom ?: return@launch
            while (kotlin.math.abs(current - linear) > step) {
                if (current < linear) current += step else current -= step
                currentCamera?.cameraControl?.setLinearZoom(current)
                delay(delayMs)
            }
            currentCamera?.cameraControl?.setLinearZoom(linear)
        }
    }

    fun setTorch(enabled: Boolean) {
        StreamState.torchRequested.set(enabled)
        currentCamera?.cameraControl?.enableTorch(enabled)
    }
}
