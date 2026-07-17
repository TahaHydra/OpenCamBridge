package com.opencambridge.android.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.content.Context
import com.opencambridge.android.state.StreamState
import com.opencambridge.android.state.StreamConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Opens the camera via CameraX ImageAnalysis and optional Preview.
 * Safely handles configuration changes using a Mutex.
 */
class MjpegStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val rebindMutex = Mutex()
    private var zoomJob: Job? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCamera: Camera? = null
    @Volatile private var activeConfig: StreamConfig? = null

    // Reusable buffers to avoid GC churn at 30-60 fps
    private var nv21Buffer: ByteArray? = null
    private var nv21RotatedBuffer: ByteArray? = null
    private val jpegStream = ByteArrayOutputStream(512 * 1024)

    // Encode-side pacing: skip camera frames beyond the requested FPS so we do
    // not burn CPU JPEG-encoding frames the HTTP layer would drop anyway.
    private var lastEncodeNs = 0L

    suspend fun start() {
        activeConfig = StreamState.currentConfig()
        StreamState.activeStreamMode.set("mjpeg")
        val provider = suspendCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    // Without this, a provider failure would leave the caller suspended forever.
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
        cameraProvider = provider
        // Propagates bind failures to the caller so the service can enter ERROR state
        // instead of pretending to stream.
        bindCameraSafe()
    }

    private suspend fun bindCameraSafe() {
        rebindMutex.withLock {
            StreamState.rebindInProgress.set(true)
            try {
                val provider = cameraProvider ?: return@withLock
                val config = activeConfig ?: StreamState.currentConfig().also { activeConfig = it }

                // Force torch off and cancel zoom before unbinding to prevent driver state corruption
                currentCamera?.cameraControl?.enableTorch(false)
                StreamState.torchEnabled.set(false)
                zoomJob?.cancel()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    provider.unbindAll()
                }

                val selector = buildSelector(config.cameraId)

                val resSelector = ResolutionPolicy.buildSelector(
                    profile = config.profile,
                    requestedWidth = config.width,
                    requestedHeight = config.height
                )

                val targetFps = config.fps

                // Pick an FPS range the *device* actually supports; hardcoded
                // ranges like [30,30] do not exist on all sensors.
                val fpsRange = FpsRanges.choose(context, config.cameraId, targetFps)

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
                        android.util.Log.w("OpenCamBridge", "Could not set FPS range $fpsRange", e)
                    }
                }
                android.util.Log.i(
                    "OpenCamBridge",
                    "Binding MJPEG CameraX camera=${config.cameraId} profile=${config.profile} requested=${config.width}x${config.height} fps=$targetFps fpsRange=$fpsRange preview=${config.localPreviewEnabled}"
                )
                // Surface the chosen AE range in the app Logs tab: if a 60 fps
                // request resolves to a variable range like [30,60] (or a lower
                // fixed range), that explains a delivered rate below target.
                com.opencambridge.android.state.AppLogger.i(
                    "Camera",
                    "Bind cam=${config.cameraId} req=${config.width}x${config.height}@$targetFps aeRange=${fpsRange ?: "default"}"
                )

                val imageAnalysis = imageAnalysisBuilder.build()

                // Seed with the current physical orientation; the service's
                // OrientationEventListener keeps it updated afterwards.
                imageAnalysis.targetRotation = StreamState.deviceSurfaceRotation.get()

                imageAnalysis.setAnalyzer(analysisExecutor, ::processFrame)
                StreamState.imageAnalysisUseCase = imageAnalysis

                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(resSelector)

                if (fpsRange != null) {
                    try {
                        androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                            .setCaptureRequestOption(
                                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                fpsRange
                            )
                    } catch (e: Exception) {
                        android.util.Log.w("OpenCamBridge", "Could not set FPS range on preview", e)
                    }
                }

                val preview = previewBuilder.build()
                StreamState.previewUseCase = preview

                val surfaceProvider = StreamState.surfaceProvider

                try {
                    val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalysis)

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (config.localPreviewEnabled) {
                            // Bind the Preview whenever it is enabled, even if the
                            // Compose PreviewView has not published its surface yet;
                            // setSurfaceProvider attaches it dynamically once ready
                            // (see StreamViewModel.setSurfaceProvider).
                            if (surfaceProvider != null) preview.setSurfaceProvider(surfaceProvider)
                            useCases.add(preview)
                        }
                        currentCamera = provider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            *useCases.toTypedArray()
                        )
                        observeCameraControls()

                        // Extract actual resolution selected by CameraX
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

                            android.util.Log.i("MjpegStreamer", "Selected Resolution: ${resolution.width}x${resolution.height} (Effective: ${effW}x${effH})")
                        }
                    }

                    StreamState.streaming.set(true)
                } catch (e: Exception) {
                    android.util.Log.e("MjpegStreamer", "bindToLifecycle failed: ${e.message}")
                    // Surface the failure instead of silently reporting STREAMING.
                    throw e
                }
            } finally {
                StreamState.rebindInProgress.set(false)
            }
        }
    }

    suspend fun stop() {
        rebindMutex.withLock {
            cameraProvider?.unbindAll()
            currentCamera = null
            activeConfig = null
            StreamState.streaming.set(false)
            StreamState.latestFrame.set(null)
        }
    }

    // ---- Camera Controls ----

    private fun observeCameraControls() {
        val camInfo = currentCamera?.cameraInfo ?: return

        val hasFlash = camInfo.hasFlashUnit()
        StreamState.hasTorch.set(hasFlash)
        if (!hasFlash && StreamState.torchEnabled.get()) {
            StreamState.torchEnabled.set(false)
            currentCamera?.cameraControl?.enableTorch(false)
        }
        // Restore the user's requested torch state after a (re)bind; the bind path
        // forces the torch off to avoid driver state corruption.
        if (hasFlash && StreamState.torchRequested.get()) {
            currentCamera?.cameraControl?.enableTorch(true)
        }

        // Synchronize state with current hardware capability
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
        // Reflect the requested state immediately so status/UI sync even if the
        // torchState observer is slow or does not emit OFF on some devices
        // (the "torch won't turn off/sync from phone" bug). The observer, when
        // it fires, confirms the same value.
        StreamState.torchEnabled.set(enabled)
        currentCamera?.cameraControl?.enableTorch(enabled)
        com.opencambridge.android.state.AppLogger.i("Torch", "set torchEnabled=$enabled (torchState now reported to /api/camera/status)")
    }

    // ---- Frame Processing ----

    /** Exponential moving average (same weighting as the total encode timing). */
    private fun ewma(prev: Double, v: Double): Double =
        if (prev <= 0.0) v else prev * 0.85 + v * 0.15

    private fun processFrame(imageProxy: ImageProxy) {
        // Drop frames if we are in the middle of a rebind to prevent native crashes
        if (StreamState.rebindInProgress.get()) {
            imageProxy.close()
            return
        }

        try {
            StreamState.rotationDegrees.set(imageProxy.imageInfo.rotationDegrees)
            StreamState.frameWidth.set(imageProxy.width)
            StreamState.frameHeight.set(imageProxy.height)

            // CPU saver 1: pace JPEG encoding to the requested FPS (with 10%
            // jitter tolerance) instead of encoding every camera frame the
            // HTTP layer would drop anyway.
            // CPU saver 2: with zero connected MJPEG clients, keep the latest
            // frame fresh at ~2 fps only (status/preview pickup stays instant,
            // battery does not burn encoding for nobody).
            val nowNs = System.nanoTime()
            val config = activeConfig ?: return
            val targetFps = config.fps.coerceIn(1, 120)
            val minIntervalNs = (1_000_000_000L / targetFps) * 9 / 10
            val idleIntervalNs = 500_000_000L
            val sinceLastNs = nowNs - lastEncodeNs
            val idle = StreamState.mjpegClientCount.get() == 0
            if (sinceLastNs < minIntervalNs || (idle && sinceLastNs < idleIntervalNs)) {
                return
            }
            lastEncodeNs = nowNs

            val width = imageProxy.width
            val height = imageProxy.height

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

            val frameSize = width * height + (width / 2) * (height / 2) * 2

            if (nv21Buffer?.size != frameSize) nv21Buffer = ByteArray(frameSize)
            val nv21 = nv21Buffer!!

            val encodeStartNs = System.nanoTime()

            // Stage A: YUV_420_888 -> NV21 conversion.
            val yuvStartNs = System.nanoTime()
            yuvToNv21(imageProxy, nv21)
            val yuvMs = (System.nanoTime() - yuvStartNs) / 1_000_000.0
            StreamState.yuvMsAvg.set(ewma(StreamState.yuvMsAvg.get(), yuvMs))

            // Rotate the ACTUAL streamed pixels so the video output of the phone
            // is upright no matter how the phone is physically held.
            //
            //   total = auto-upright + manual offset
            //
            // - auto-upright: imageInfo.rotationDegrees. Because the service
            //   feeds the physical device orientation into targetRotation, this
            //   value tracks the phone being vertical, horizontal, or upside
            //   down (it is NOT a constant per device).
            // - manual offset: the user's Rotate button (displayRotation), for
            //   fixed mounts or intentional flips.
            //
            // The rotation happens on the NV21 buffer BEFORE JPEG encoding (a
            // memory permutation — no decode/re-encode). Every consumer (desktop
            // preview, Rust producer, /obs, OBS) reads this same /stream.mjpeg,
            // so nothing downstream may rotate again; portrait frames are
            // letterboxed by the producer into the fixed 16:9 virtual camera.
            val autoRot = imageProxy.imageInfo.rotationDegrees
            val manualRot = (config.displayRotation.toIntOrNull() ?: 0).mod(360)
            val totalRot = (autoRot + manualRot).mod(360)

            val outBuf: ByteArray
            val outW: Int
            val outH: Int
            if (totalRot != 0) {
                if (nv21RotatedBuffer?.size != frameSize) nv21RotatedBuffer = ByteArray(frameSize)
                val dst = nv21RotatedBuffer!!
                // Stage B: NV21 rotation (only when a rotation is applied).
                val rotStartNs = System.nanoTime()
                rotateNv21(nv21, dst, width, height, totalRot)
                val rotMs = (System.nanoTime() - rotStartNs) / 1_000_000.0
                StreamState.rotateMsAvg.set(ewma(StreamState.rotateMsAvg.get(), rotMs))
                outBuf = dst
                if (totalRot % 180 != 0) { outW = height; outH = width } else { outW = width; outH = height }
            } else {
                // No rotation this frame: record 0 so the average decays toward it.
                StreamState.rotateMsAvg.set(ewma(StreamState.rotateMsAvg.get(), 0.0))
                outBuf = nv21
                outW = width
                outH = height
            }
            StreamState.rotationApplied.set(totalRot != 0)
            StreamState.encodedWidth.set(outW)
            StreamState.encodedHeight.set(outH)
            StreamState.resizeNeeded.set(outW != config.width || outH != config.height)

            val quality = StreamState.jpegQuality.get()
            // Stage C: YuvImage build + compressToJpeg.
            val jpegStartNs = System.nanoTime()
            val yuvImage = YuvImage(outBuf, ImageFormat.NV21, outW, outH, null)
            jpegStream.reset()
            yuvImage.compressToJpeg(Rect(0, 0, outW, outH), quality, jpegStream)
            val jpegMs = (System.nanoTime() - jpegStartNs) / 1_000_000.0
            StreamState.jpegMsAvg.set(ewma(StreamState.jpegMsAvg.get(), jpegMs))

            val encodeMs = (System.nanoTime() - encodeStartNs) / 1_000_000.0
            StreamState.androidEncodeMsAvg.set(ewma(StreamState.androidEncodeMsAvg.get(), encodeMs))

            StreamState.latestFrame.set(jpegStream.toByteArray())
            StreamState.latestFrameRevision.incrementAndGet()

            val now = System.currentTimeMillis()
            StreamState.framesThisSecond.incrementAndGet()
            val windowStart = StreamState.fpsWindowStartMs.get()

            if (now - windowStart >= 1000L) {
                if (StreamState.fpsWindowStartMs.compareAndSet(windowStart, now)) {
                    val count = StreamState.framesThisSecond.getAndSet(0)
                    StreamState.actualFps.set(count)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MjpegStreamer", "Frame processing failed", e)
        } finally {
            imageProxy.close()
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

    /**
     * Rotates an NV21 frame by 90/180/270 degrees clockwise into [dst], as a
     * pure memory permutation — no JPEG decode/re-encode, so it is cheap enough
     * to run per frame. For 90/270 the output dimensions are (height x width).
     * NV21 layout: full-res Y plane, then interleaved V,U at quarter resolution.
     */
    private fun rotateNv21(src: ByteArray, dst: ByteArray, width: Int, height: Int, degrees: Int) {
        val ySize = width * height
        val total = ySize + ySize / 2
        when (degrees) {
            90 -> {
                var i = 0
                for (x in 0 until width) {
                    for (y in height - 1 downTo 0) {
                        dst[i++] = src[y * width + x]
                    }
                }
                i = ySize
                for (x in 0 until width step 2) {
                    for (y in height / 2 - 1 downTo 0) {
                        val p = ySize + y * width + x
                        dst[i++] = src[p]     // V
                        dst[i++] = src[p + 1] // U
                    }
                }
            }
            180 -> {
                var i = 0
                for (p in ySize - 1 downTo 0) {
                    dst[i++] = src[p]
                }
                i = ySize
                var p = total - 2
                while (p >= ySize) {
                    dst[i++] = src[p]     // V
                    dst[i++] = src[p + 1] // U
                    p -= 2
                }
            }
            270 -> {
                var i = 0
                for (x in width - 1 downTo 0) {
                    for (y in 0 until height) {
                        dst[i++] = src[y * width + x]
                    }
                }
                i = ySize
                for (x in width - 2 downTo 0 step 2) {
                    for (y in 0 until height / 2) {
                        val p = ySize + y * width + x
                        dst[i++] = src[p]     // V
                        dst[i++] = src[p + 1] // U
                    }
                }
            }
            else -> System.arraycopy(src, 0, dst, 0, total)
        }
    }

    private fun buildSelector(cameraId: String): CameraSelector =
        CameraSelectors.forCameraId(cameraId)
}
