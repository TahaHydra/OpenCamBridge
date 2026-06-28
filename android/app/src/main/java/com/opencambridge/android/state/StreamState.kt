package com.opencambridge.android.state

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared mutable state for the stream. Accessed from both the UI thread and
 * Ktor server coroutines — uses atomic types for thread safety.
 */
enum class LifecycleState {
    STOPPED, STARTING, STREAMING, REBINDING, STOPPING, ERROR
}

object StreamState {
    val revision = AtomicLong(0L)
    val updatedAtMillis = AtomicLong(System.currentTimeMillis())
    val lastUpdatedBy = AtomicReference("system")

    val streaming = AtomicBoolean(false) // Deprecated, use lifecycleState
    val lifecycleState = AtomicReference(LifecycleState.STOPPED)
    val lastError = AtomicReference("")

    
    // Security & Network
    val accessMode = AtomicReference("usbOnly") // usbOnly, lanOpen, lanToken
    val port = AtomicInteger(8080)
    val accessToken = AtomicReference("")

    val streamMode = AtomicReference("mjpeg") // mjpeg, h264
    val h264Bitrate = AtomicInteger(4000000)
    val h264KeyframeInterval = AtomicInteger(2)

    val cameraId = AtomicReference("0")
    val width = AtomicInteger(1280) // requested capture width
    val height = AtomicInteger(720) // requested capture height
    val outputWidth = AtomicInteger(1280) // requested output width
    val outputHeight = AtomicInteger(720) // requested output height
    val profile = AtomicReference("balanced")
    
    val jpegQuality = AtomicInteger(85)
    val fps = AtomicInteger(30)
    val actualFps = AtomicInteger(0)
    val framesThisSecond = AtomicInteger(0)
    val fpsWindowStartMs = AtomicLong(System.currentTimeMillis())
    val androidEncodeMsAvg = AtomicReference(0.0)
    val previewFitMode = AtomicReference("fill")
    val aspectRatio = AtomicReference("auto") // auto, 16:9, 4:3
    val zoomSpeed = AtomicReference("normal") // slow, normal, fast
    val displayRotation = AtomicReference("auto") // auto, 0, 90, 180, 270
    val mirror = AtomicBoolean(false)
    
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
    val sensorOrientation = AtomicInteger(0)
    val frameWidth = AtomicInteger(0)
    val frameHeight = AtomicInteger(0)
    val encodedWidth = AtomicInteger(0)
    val encodedHeight = AtomicInteger(0)
    val rotationApplied = AtomicBoolean(false)
    
    // Resolution Selection Metrics
    val selectedRawWidth = AtomicInteger(0)
    val selectedRawHeight = AtomicInteger(0)
    val selectedEffectiveWidth = AtomicInteger(0)
    val selectedEffectiveHeight = AtomicInteger(0)
    val normalizedForPolicy = AtomicBoolean(false)
    val resolutionPolicy = AtomicReference("unknown")
    val fallbackUsed = AtomicBoolean(false)
    
    val requestedAspectRatio = AtomicReference("unknown")
    val selectedAspectRatio = AtomicReference("unknown")
    val aspectRatioMatch = AtomicBoolean(false)
    val resizeNeeded = AtomicBoolean(false)

    // Bandwidth metrics
    val targetBandwidthMbps = AtomicInteger(0)
    val bytesSentThisSecond = AtomicLong(0L)
    val estimatedMbps = AtomicReference("0.0")

    /** Latest JPEG frame bytes, updated by MjpegStreamer. Null before first frame. */
    val latestFrame = AtomicReference<ByteArray?>(null)
    val latestFrameRevision = AtomicLong(0L)
    
    /** SurfaceProvider for CameraX Preview use case */
    var surfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null
    
    /** The active Preview UseCase (if any). Enables dynamic surface rebinding without tearing down CameraX. */
    var previewUseCase: androidx.camera.core.Preview? = null
    
    /** The active ImageAnalysis UseCase (if any). Enables dynamic targetRotation updates. */
    var imageAnalysisUseCase: androidx.camera.core.ImageAnalysis? = null
    
    fun incrementRevision(source: String) {
        revision.incrementAndGet()
        updatedAtMillis.set(System.currentTimeMillis())
        lastUpdatedBy.set(source)
    }

    fun toStatusDto(): StreamStatusDto = StreamStatusDto(
        revision = revision.get(),
        updatedAtMillis = updatedAtMillis.get(),
        lastUpdatedBy = lastUpdatedBy.get(),
        streaming = streaming.get(),
        lifecycleState = lifecycleState.get().name,
        lastError = lastError.get(),
        accessMode = accessMode.get(),
        port = port.get(),
        tokenRequired = accessMode.get() == "lanToken",
        allowLan = accessMode.get() != "usbOnly",
        streamMode = streamMode.get(),
        h264Bitrate = h264Bitrate.get(),
        h264KeyframeInterval = h264KeyframeInterval.get(),
        cameraId = cameraId.get(),
        width = width.get(),
        height = height.get(),
        outputWidth = outputWidth.get(),
        outputHeight = outputHeight.get(),
        profile = profile.get(),
        fps = fps.get(),
        jpegQuality = jpegQuality.get(),
        previewFitMode = previewFitMode.get(),
        aspectRatio = aspectRatio.get(),
        zoomSpeed = zoomSpeed.get(),
        localPreviewEnabled = localPreviewEnabled.get(),
        rebindInProgress = rebindInProgress.get(),
        hasTorch = hasTorch.get(),
        rotationDegrees = rotationDegrees.get(),
        sensorOrientation = sensorOrientation.get(),
        frameWidth = frameWidth.get(),
        frameHeight = frameHeight.get(),
        encodedWidth = encodedWidth.get(),
        encodedHeight = encodedHeight.get(),
        rotationApplied = rotationApplied.get(),
        targetBandwidthMbps = targetBandwidthMbps.get(),
        estimatedMbps = estimatedMbps.get(),
        isFramePortrait = frameHeight.get() > frameWidth.get(),
        isFrameLandscape = frameWidth.get() >= frameHeight.get(),
        displayRotation = displayRotation.get(),
        mirror = mirror.get(),
        requestedAspectRatio = requestedAspectRatio.get(),
        selectedAspectRatio = selectedAspectRatio.get(),
        aspectRatioMatch = aspectRatioMatch.get(),
        resizeNeeded = resizeNeeded.get(),
        selectedRawWidth = selectedRawWidth.get(),
        selectedRawHeight = selectedRawHeight.get(),
        selectedEffectiveWidth = selectedEffectiveWidth.get(),
        selectedEffectiveHeight = selectedEffectiveHeight.get(),
        normalizedForPolicy = normalizedForPolicy.get(),
        resolutionPolicy = resolutionPolicy.get(),
        fallbackUsed = fallbackUsed.get()
    )
}

@Serializable
data class StreamStatusDto(
    val revision: Long,
    val updatedAtMillis: Long,
    val lastUpdatedBy: String,
    val streaming: Boolean,
    val lifecycleState: String,
    val lastError: String,
    val accessMode: String,
    val port: Int,
    val tokenRequired: Boolean,
    val allowLan: Boolean,
    val streamMode: String,
    val h264Bitrate: Int,
    val h264KeyframeInterval: Int,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val profile: String,
    val fps: Int,
    val jpegQuality: Int,
    val previewFitMode: String,
    val aspectRatio: String,
    val zoomSpeed: String,
    val localPreviewEnabled: Boolean,
    val rebindInProgress: Boolean,
    val hasTorch: Boolean,
    val rotationDegrees: Int,
    val sensorOrientation: Int = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val encodedWidth: Int = 0,
    val encodedHeight: Int = 0,
    val rotationApplied: Boolean = false,
    val targetBandwidthMbps: Int = 0,
    val estimatedMbps: String = "0.0",
    val isFramePortrait: Boolean = false,
    val isFrameLandscape: Boolean = false,
    val displayRotation: String,
    val mirror: Boolean,
    val requestedAspectRatio: String = "unknown",
    val selectedAspectRatio: String = "unknown",
    val aspectRatioMatch: Boolean = false,
    val resizeNeeded: Boolean = false,
    val selectedRawWidth: Int = 0,
    val selectedRawHeight: Int = 0,
    val selectedEffectiveWidth: Int = 0,
    val selectedEffectiveHeight: Int = 0,
    val normalizedForPolicy: Boolean = false,
    val resolutionPolicy: String = "unknown",
    val fallbackUsed: Boolean = false
)
