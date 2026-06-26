package com.opencambridge.android.state

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared mutable state for the stream. Accessed from both the UI thread and
 * Ktor server coroutines — uses atomic types for thread safety.
 */
object StreamState {
    val streaming = AtomicBoolean(false)
    val cameraId = AtomicReference("0")
    val width = AtomicInteger(1280)
    val height = AtomicInteger(720)
    val jpegQuality = AtomicInteger(85)
    val fps = AtomicInteger(30)
    val previewFitMode = AtomicReference("fit")
    val aspectRatio = AtomicReference("auto") // auto, 16:9, 4:3
    val zoomSpeed = AtomicReference("normal") // slow, normal, fast
    
    // UI/Preview
    val localPreviewEnabled = AtomicBoolean(false)
    val torchEnabled = AtomicBoolean(false)
    val autofocusEnabled = AtomicBoolean(true) // Default true for continuous AF
    
    // Transient hardware state
    val rebindInProgress = AtomicBoolean(false)
    val zoomRatio = AtomicReference(1.0f)
    val linearZoom = AtomicReference(0.0f)
    val hasTorch = AtomicBoolean(false)
    val rotationDegrees = AtomicInteger(0)

    /** Latest JPEG frame bytes, updated by MjpegStreamer. Null before first frame. */
    val latestFrame = AtomicReference<ByteArray?>(null)
    
    /** SurfaceProvider for CameraX Preview use case */
    var surfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null

    fun toStatusDto(): StreamStatusDto = StreamStatusDto(
        streaming = streaming.get(),
        cameraId = cameraId.get(),
        width = width.get(),
        height = height.get(),
        fps = fps.get(),
        jpegQuality = jpegQuality.get(),
        previewFitMode = previewFitMode.get(),
        aspectRatio = aspectRatio.get(),
        zoomSpeed = zoomSpeed.get(),
        localPreviewEnabled = localPreviewEnabled.get(),
        rebindInProgress = rebindInProgress.get(),
        hasTorch = hasTorch.get(),
        rotationDegrees = rotationDegrees.get()
    )
}

@Serializable
data class StreamStatusDto(
    val streaming: Boolean,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val jpegQuality: Int,
    val previewFitMode: String,
    val aspectRatio: String,
    val zoomSpeed: String,
    val localPreviewEnabled: Boolean,
    val rebindInProgress: Boolean,
    val hasTorch: Boolean,
    val rotationDegrees: Int
)
