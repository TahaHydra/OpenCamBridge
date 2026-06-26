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

    // Reusable buffer to avoid GC churn
    private var nv21Buffer: ByteArray? = null

    suspend fun start() = suspendCoroutine<Unit> { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            scope.launch { 
                bindCameraSafe() 
                cont.resume(Unit)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private suspend fun bindCameraSafe() {
        rebindMutex.withLock {
            StreamState.rebindInProgress.set(true)
            try {
                val provider = cameraProvider ?: return@withLock
                
                // Force torch off and cancel zoom before unbinding to prevent driver state corruption
                currentCamera?.cameraControl?.enableTorch(false)
                StreamState.torchEnabled.set(false)
                zoomJob?.cancel()

                provider.unbindAll()

                val selector = buildSelector(StreamState.cameraId.get())

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(
                        android.util.Size(StreamState.width.get(), StreamState.height.get())
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor, ::processFrame)
                StreamState.imageAnalysisUseCase = imageAnalysis
                
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(StreamState.width.get(), StreamState.height.get()))
                    .build()
                StreamState.previewUseCase = preview

                val surfaceProvider = StreamState.surfaceProvider
                if (StreamState.localPreviewEnabled.get() && surfaceProvider != null) {
                    preview.setSurfaceProvider(surfaceProvider)
                }

                try {
                    val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalysis, preview)
                    
                    currentCamera = provider.bindToLifecycle(
                        lifecycleOwner, 
                        selector, 
                        *useCases.toTypedArray()
                    )
                    
                    observeCameraControls()
                    StreamState.streaming.set(true)
                } catch (e: Exception) {
                    android.util.Log.e("MjpegStreamer", "bindToLifecycle failed: ${e.message}")
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
            StreamState.streaming.set(false)
            StreamState.latestFrame.set(null)
        }
    }

    fun rebindIfStreaming() {
        scope.launch {
            if (StreamState.streaming.get()) {
                bindCameraSafe()
            }
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
        currentCamera?.cameraControl?.enableTorch(enabled)
    }

    // ---- Frame Processing ----

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
            
            val width = imageProxy.width
            val height = imageProxy.height
            val frameSize = width * height + (width / 2) * (height / 2) * 2

            if (nv21Buffer?.size != frameSize) nv21Buffer = ByteArray(frameSize)
            val nv21 = nv21Buffer!!
            
            yuvToNv21(imageProxy, nv21)
            
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), StreamState.jpegQuality.get(), out)
            
            StreamState.latestFrame.set(out.toByteArray())
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

    private fun buildSelector(cameraId: String): CameraSelector = when (cameraId) {
        "1"  -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> CameraSelector.DEFAULT_BACK_CAMERA
    }
}
