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
import kotlin.coroutines.resume
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

    private var spsPpsBuffer: ByteArray? = null
    private val clients = CopyOnWriteArrayList<Channel<ByteArray>>()

    // Throttling for MJPEG generation
    private var lastJpegTime = 0L
    private val jpegThrottleMs = 200L // 5 FPS for web preview fallback

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
                
                currentCamera?.cameraControl?.enableTorch(false)
                StreamState.torchEnabled.set(false)
                zoomJob?.cancel()
                
                stopCodec()
                provider.unbindAll()

                val selector = buildSelector(StreamState.cameraId.get())
                val width = StreamState.width.get()
                val height = StreamState.height.get()
                val fps = StreamState.fps.get()

                startCodec(width, height, fps)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(width, height))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor, ::processFrame)
                StreamState.imageAnalysisUseCase = imageAnalysis
                
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(width, height))
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
                    Log.e("H264Streamer", "bindToLifecycle failed: ${e.message}")
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
            stopCodec()
            StreamState.streaming.set(false)
            StreamState.latestFrame.set(null)
            clients.forEach { it.close() }
            clients.clear()
        }
    }

    fun rebindIfStreaming() {
        scope.launch {
            if (StreamState.streaming.get()) {
                bindCameraSafe()
            }
        }
    }

    // ---- Channels ----

    fun subscribe(): Channel<ByteArray> {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        clients.add(channel)
        
        // Send SPS/PPS immediately if available
        val sps = spsPpsBuffer
        if (sps != null) {
            channel.trySend(sps)
        }
        return channel
    }

    fun unsubscribe(channel: Channel<ByteArray>) {
        clients.remove(channel)
        channel.close()
    }

    // ---- Codec Management ----

    private fun startCodec(width: Int, height: Int, fps: Int) {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, StreamState.h264Bitrate.get())
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, StreamState.h264KeyframeInterval.get())
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            isEncoding = true
            encodeJob = scope.launch {
                drainCodec()
            }
            Log.i("H264Streamer", "Started H.264 Codec: ${width}x${height} @ ${fps}fps, ${StreamState.h264Bitrate.get()} bps")
        } catch (e: Exception) {
            Log.e("H264Streamer", "Failed to start MediaCodec", e)
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
                        } else {
                            // Broadcast
                            for (client in clients) {
                                client.trySend(data)
                            }
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
        if (StreamState.rebindInProgress.get() || !isEncoding) {
            imageProxy.close()
            return
        }
        
        val codec = mediaCodec
        if (codec == null) {
            imageProxy.close()
            return
        }

        try {
            StreamState.rotationDegrees.set(imageProxy.imageInfo.rotationDegrees)
            StreamState.frameWidth.set(imageProxy.width)
            StreamState.frameHeight.set(imageProxy.height)

            // Feed frame to encodered H.264
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    // Extract YUV to NV12 for encoder
                    val nv12Bytes = yuvToNv12(imageProxy)
                    inputBuffer.clear()
                    inputBuffer.put(nv12Bytes)
                    val pts = imageProxy.imageInfo.timestamp / 1000 // Convert nanoseconds to microseconds
                    codec.queueInputBuffer(inputBufferIndex, 0, nv12Bytes.size, pts, 0)
                }
            }

            // 2. Feed MJPEG fallback if needed
            val now = System.currentTimeMillis()
            if (now - lastJpegTime > jpegThrottleMs) {
                val jpegBytes = yuvToJpeg(imageProxy, StreamState.jpegQuality.get())
                StreamState.latestFrame.set(jpegBytes)
                lastJpegTime = now
            }
        } catch (e: Exception) {
            Log.e("H264Streamer", "Frame processing failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun yuvToNv12(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val chromaH = height / 2
        val chromaW = width / 2

        val nv12 = ByteArray(width * height + chromaW * chromaH * 2)

        val yBuf = yPlane.buffer
        var dstOffset = 0
        for (row in 0 until height) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv12, dstOffset, width)
            dstOffset += width
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val srcIndex = row * uvRowStride + col * uvPixelStride
                // NV12 expects U then V.
                uBuf.position(srcIndex)
                nv12[dstOffset++] = uBuf.get()
                vBuf.position(srcIndex)
                nv12[dstOffset++] = vBuf.get()
            }
        }
        return nv12
    }

    private fun yuvToJpeg(image: ImageProxy, quality: Int): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val chromaH = height / 2
        val chromaW = width / 2

        val nv21 = ByteArray(width * height + chromaW * chromaH * 2)

        val yBuf = yPlane.buffer
        var dstOffset = 0
        for (row in 0 until height) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, dstOffset, width)
            dstOffset += width
        }

        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val srcIndex = row * uvRowStride + col * uvPixelStride
                vBuf.position(srcIndex)
                nv21[dstOffset++] = vBuf.get()
                uBuf.position(srcIndex)
                nv21[dstOffset++] = uBuf.get()
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }

    private fun buildSelector(cameraId: String): CameraSelector = when (cameraId) {
        "1" -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun observeCameraControls() {
        val camInfo = currentCamera?.cameraInfo ?: return
        
        val hasFlash = camInfo.hasFlashUnit()
        StreamState.hasTorch.set(hasFlash)
        if (!hasFlash && StreamState.torchEnabled.get()) {
            StreamState.torchEnabled.set(false)
            currentCamera?.cameraControl?.enableTorch(false)
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
        currentCamera?.cameraControl?.enableTorch(enabled)
    }
}
