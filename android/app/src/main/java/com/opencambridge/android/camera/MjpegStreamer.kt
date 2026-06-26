package com.opencambridge.android.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.opencambridge.android.state.StreamState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Opens the camera via CameraX ImageAnalysis, converts YUV_420_888 frames to JPEG, and
 * stores the latest frame in StreamState.latestFrame for the MJPEG server to pick up.
 *
 * Call start() to begin capture and stop() to unbind.
 */
class MjpegStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
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

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
            StreamState.streaming.set(true)
        } catch (e: Exception) {
            android.util.Log.e("MjpegStreamer", "bindToLifecycle failed: ${e.message}")
        }
    }

    fun stop() {
        cameraProvider?.unbindAll()
        StreamState.streaming.set(false)
        StreamState.latestFrame.set(null)
    }

    /** Re-bind with a different camera id. No-op if not currently streaming. */
    fun switchCamera(newCameraId: String) {
        StreamState.cameraId.set(newCameraId)
        if (StreamState.streaming.get()) {
            cameraProvider?.unbindAll()
            bindCamera()
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val jpegBytes = yuvToJpeg(imageProxy, StreamState.jpegQuality.get())
            StreamState.latestFrame.set(jpegBytes)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Converts YUV_420_888 to JPEG via Android's YuvImage (NV21 path).
     * This is fast and avoids a full Bitmap decode/encode for every frame.
     */
    private fun yuvToJpeg(image: ImageProxy, quality: Int): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // Interleave into NV21 byte layout: Y plane then VU interleaved.
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    /**
     * Maps a camera id string (from Camera2 / CameraRepository) to a CameraX selector.
     *
     * "0" → back camera (conventional Camera2 mapping)
     * "1" → front camera
     * Anything else → back camera as fallback
     */
    private fun buildSelector(cameraId: String): CameraSelector = when (cameraId) {
        "1"  -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> CameraSelector.DEFAULT_BACK_CAMERA
    }
}
