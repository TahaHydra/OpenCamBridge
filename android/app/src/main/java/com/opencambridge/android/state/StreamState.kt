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
    STOPPED, STARTING, STREAMING, STOPPING, RECONFIGURING, RECOVERING, FAILED
}

@Serializable
data class StreamConfig(
    val accessMode: String = "usbOnly",
    val port: Int = 8080,
    val accessToken: String = "",
    val streamMode: String = "h264",
    val h264Bitrate: Int = 4_000_000,
    val h264KeyframeInterval: Int = H264SettingsPolicy.DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    val cameraId: String = "0",
    val width: Int = 1920,
    val height: Int = 1080,
    val outputWidth: Int = 1920,
    val outputHeight: Int = 1080,
    val profile: String = "adaptive",
    val jpegQuality: Int = 85,
    val fps: Int = 60,
    val previewFitMode: String = "fill",
    val aspectRatio: String = "auto",
    val zoomSpeed: String = "normal",
    val displayRotation: String = "auto",
    val mirror: Boolean = false,
    val localPreviewEnabled: Boolean = false,
    val targetBandwidthMbps: Int = 0
)

/** Facades backed by fields in the immutable pipeline snapshot. They retain the
 * small get/set API used by capture code without storing control state in
 * unrelated atomics. */
class SnapshotValue<T> internal constructor(
    private val read: () -> T,
    private val write: (T) -> Unit
) {
    fun get(): T = read()
    fun set(value: T) = write(value)
}

class SnapshotReadValue<T> internal constructor(private val read: () -> T) {
    fun get(): T = read()
}

class SnapshotCounter internal constructor(
    private val read: () -> Long,
    private val increment: () -> Long
) {
    fun get(): Long = read()
    fun incrementAndGet(): Long = increment()
}

private data class SelectedPipeline(
    val generation: Long,
    val cameraId: String,
    val streamMode: String,
    val captureEngine: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val encoderName: String?,
    val hardwareEncoder: Boolean
)

private data class ActualPipeline(
    val generation: Long,
    val width: Int,
    val height: Int,
    val captureFps: Int,
    val encodedFps: Int,
    val encodedBitrate: Int
)

private data class FallbackState(
    val generation: Long,
    val active: Boolean,
    val reason: String
)

private data class PipelineSnapshot(
    val revision: Long = 0,
    val generation: Long = 0,
    val lifecycle: LifecycleState = LifecycleState.STOPPED,
    val desired: StreamConfig = StreamConfig(),
    val selected: SelectedPipeline? = null,
    val actual: ActualPipeline? = null,
    val fallback: FallbackState? = null,
    val phonePreviewActive: Boolean = false,
    val phonePreviewFailureReason: String = "",
    val lastRequestId: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val lastUpdatedBy: String = "system"
)

object StreamState {
    private val pipelineSnapshot = AtomicReference(PipelineSnapshot())

    private fun updatePipelineSnapshot(update: (PipelineSnapshot) -> PipelineSnapshot): PipelineSnapshot {
        while (true) {
            val previous = pipelineSnapshot.get()
            val next = update(previous)
            if (pipelineSnapshot.compareAndSet(previous, next)) return next
        }
    }

    val revision = SnapshotReadValue { pipelineSnapshot.get().revision }
    val pipelineGeneration = SnapshotCounter(
        read = { pipelineSnapshot.get().generation },
        increment = {
            updatePipelineSnapshot {
                it.copy(
                    generation = it.generation + 1,
                    selected = null,
                    actual = null,
                    fallback = null,
                    phonePreviewActive = false,
                    phonePreviewFailureReason = if (it.desired.localPreviewEnabled) {
                        "Waiting for a preview target in pipeline generation ${it.generation + 1}"
                    } else ""
                )
            }.generation
        }
    )
    val updatedAtMillis = SnapshotReadValue { pipelineSnapshot.get().updatedAtMillis }
    val lastUpdatedBy = SnapshotReadValue { pipelineSnapshot.get().lastUpdatedBy }
    val phonePreviewActive = SnapshotReadValue { pipelineSnapshot.get().phonePreviewActive }
    val phonePreviewFailureReason = SnapshotReadValue { pipelineSnapshot.get().phonePreviewFailureReason }

    val lifecycleState = SnapshotValue({ pipelineSnapshot.get().lifecycle }) { value ->
        updatePipelineSnapshot { it.copy(lifecycle = value) }
    }
    val lastError = AtomicReference("")

    // When true, noisy diagnostics (all log levels) are shown; when false,
    // only WARN/ERROR are surfaced in the phone Logs tab.
    val developerMode = AtomicBoolean(false)


    // Security & Network
    val accessMode = AtomicReference("usbOnly") // usbOnly, lanOpen, lanToken
    val port = AtomicInteger(8080)
    val accessToken = AtomicReference("")

    val streamMode = AtomicReference("h264") // h264 preferred; mjpeg compatibility
    val h264Bitrate = AtomicInteger(4000000)
    val h264KeyframeInterval = AtomicInteger(H264SettingsPolicy.DEFAULT_KEYFRAME_INTERVAL_SECONDS)
    val activeStreamMode = AtomicReference("mjpeg")
    val fallbackReason = AtomicReference("")
    val h264Failed = AtomicBoolean(false)

    val cameraId = AtomicReference("0")
    val width = AtomicInteger(1920) // requested capture width
    val height = AtomicInteger(1080) // requested capture height
    val outputWidth = AtomicInteger(1920) // requested output width
    val outputHeight = AtomicInteger(1080) // requested output height
    val profile = AtomicReference("adaptive")

    val jpegQuality = AtomicInteger(85)
    val fps = AtomicInteger(60)
    val actualFps = AtomicInteger(0)
    val captureFps = AtomicInteger(0)
    val encodedFps = AtomicInteger(0)
    val selectedFps = AtomicInteger(0)
    val encodedBitrate = AtomicInteger(0)
    val actualBitrate = AtomicInteger(0)
    val encoderName = AtomicReference("")
    val hardwareEncoder = AtomicBoolean(false)
    val captureEngine = AtomicReference("")
    val cameraSessionFps = AtomicInteger(0)
    val gpuBridgeFps = AtomicInteger(0)
    val capturePathError = AtomicReference("")
    val framesThisSecond = AtomicInteger(0)
    val fpsWindowStartMs = AtomicLong(System.currentTimeMillis())
    val androidEncodeMsAvg = AtomicReference(0.0)
    // Split capture-pipeline timing (EWMA ms), so the total androidEncodeMsAvg
    // can be attributed to its stages: YUV_420_888 -> NV21 conversion, NV21
    // rotation (0 when no rotation), and YuvImage.compressToJpeg.
    val yuvMsAvg = AtomicReference(0.0)
    val jpegMsAvg = AtomicReference(0.0)
    val rotateMsAvg = AtomicReference(0.0)
    val previewFitMode = AtomicReference("fill")
    val aspectRatio = AtomicReference("auto") // auto, 16:9, 4:3
    val zoomSpeed = AtomicReference("normal") // slow, normal, fast
    val displayRotation = AtomicReference("auto") // auto, 0, 90, 180, 270
    val mirror = AtomicBoolean(false)

    // UI/Preview
    val localPreviewEnabled = AtomicBoolean(false)
    val torchEnabled = AtomicBoolean(false)
    /** What the user asked the torch to be. Used to restore torch after a camera rebind. */
    val torchRequested = AtomicBoolean(false)
    val autofocusEnabled = AtomicBoolean(true) // Default true for continuous AF

    /** Physical device orientation as a Surface.ROTATION_* value, kept current
     *  by StreamService's OrientationEventListener. Applied as targetRotation on
     *  the ImageAnalysis use case so imageInfo.rotationDegrees always describes
     *  the rotation needed to make the frame upright for how the phone is
     *  actually held (vertical, horizontal, upside down). */
    val deviceSurfaceRotation = AtomicInteger(android.view.Surface.ROTATION_0)

    // Transient hardware state
    val rebindInProgress = AtomicBoolean(false)
    val zoomRatio = AtomicReference(1.0f)
    val linearZoom = AtomicReference(0.0f)
    val hasTorch = AtomicBoolean(false)
    val rotationDegrees = AtomicInteger(0)
    val sensorOrientation = AtomicInteger(0)
    /** Sensor/device correction without the manual offset; decides the shape of
     *  the picture the camera surface presents to the local preview. */
    val autoRotation = AtomicInteger(0)
    /** Rotation that makes the camera buffer upright on THIS phone's screen. */
    val previewRotation = AtomicInteger(0)
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

    // Connected stream clients. Used for metrics and to skip JPEG encoding
    // when nobody is consuming the MJPEG stream.
    val mjpegClientCount = AtomicInteger(0)
    val h264ClientCount = AtomicInteger(0)

    /** Latest JPEG frame bytes, updated by MjpegStreamer. Null before first frame. */
    val latestFrame = AtomicReference<ByteArray?>(null)
    val latestFrameRevision = AtomicLong(0L)

    /** SurfaceProvider for CameraX Preview use case */
    var surfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null

    /** Native preview surface used only by the Camera2/MediaCodec H.264 path. */
    val camera2PreviewSurface = AtomicReference<android.view.Surface?>(null)

    /** The active Preview UseCase (if any). Enables dynamic surface rebinding without tearing down CameraX. */
    var previewUseCase: androidx.camera.core.Preview? = null

    /** The active ImageAnalysis UseCase (if any). Enables dynamic targetRotation updates. */
    var imageAnalysisUseCase: androidx.camera.core.ImageAnalysis? = null

    fun currentConfig(): StreamConfig = pipelineSnapshot.get().desired

    /** Atomically publishes authoritative startup settings, then mirrors legacy
     * atomics for capture/control code that has not yet been migrated.
     * Pipeline starts always capture currentConfig() once, so those mirrors can
     * never produce a mixed camera/size/FPS generation. */
    fun publishConfig(config: StreamConfig) {
        updatePipelineSnapshot { it.copy(desired = config) }
        mirrorLegacyConfig(config)
    }

    /** Publishes desired settings and revision metadata in one compare-and-set.
     * Readers never see a new desired configuration under an old revision. */
    fun publishConfigRevision(config: StreamConfig, source: String, requestId: String?): Long {
        val snapshot = updatePipelineSnapshot {
            val shapeChanged = it.desired.cameraId != config.cameraId ||
                it.desired.streamMode != config.streamMode ||
                it.desired.width != config.width || it.desired.height != config.height ||
                it.desired.fps != config.fps || it.desired.profile != config.profile ||
                it.desired.displayRotation != config.displayRotation ||
                it.desired.mirror != config.mirror ||
                it.desired.h264KeyframeInterval != config.h264KeyframeInterval
            it.copy(
                desired = config,
                revision = it.revision + 1,
                selected = if (shapeChanged) null else it.selected,
                actual = if (shapeChanged) null else it.actual,
                fallback = if (shapeChanged) null else it.fallback,
                phonePreviewActive = if (shapeChanged || !config.localPreviewEnabled) false else it.phonePreviewActive,
                phonePreviewFailureReason = when {
                    !config.localPreviewEnabled -> ""
                    shapeChanged -> "Waiting for preview session reconfiguration"
                    else -> it.phonePreviewFailureReason
                },
                lastRequestId = requestId,
                updatedAtMillis = System.currentTimeMillis(),
                lastUpdatedBy = source
            )
        }
        mirrorLegacyConfig(config)
        return snapshot.revision
    }

    fun publishRuntimeRevision(source: String, requestId: String): Long = updatePipelineSnapshot {
        it.copy(
            revision = it.revision + 1,
            lastRequestId = requestId,
            updatedAtMillis = System.currentTimeMillis(),
            lastUpdatedBy = source
        )
    }.revision

    /** Publish runtime selection as part of the same immutable generation as
     * lifecycle and desired state. Legacy atomics remain compatibility
     * projections only; status is built exclusively from this snapshot. */
    fun publishSelectedPipeline(
        generation: Long,
        cameraId: String,
        streamMode: String,
        captureEngine: String,
        width: Int,
        height: Int,
        fps: Int,
        encoderName: String?,
        hardwareEncoder: Boolean
    ) {
        updatePipelineSnapshot { current ->
            if (current.generation != generation) return@updatePipelineSnapshot current
            current.copy(
                selected = SelectedPipeline(
                    generation, cameraId, streamMode, captureEngine,
                    width, height, fps, encoderName, hardwareEncoder
                ),
                actual = current.actual?.takeIf { it.generation == current.generation }
            )
        }
    }

    fun publishActualPipeline(
        generation: Long,
        width: Int,
        height: Int,
        captureFps: Int,
        encodedFps: Int,
        encodedBitrate: Int
    ) {
        updatePipelineSnapshot { current ->
            if (current.generation != generation || current.selected?.generation != generation) {
                return@updatePipelineSnapshot current
            }
            current.copy(
                actual = ActualPipeline(
                    generation, width, height, captureFps, encodedFps, encodedBitrate
                )
            )
        }
    }

    fun publishFallback(active: Boolean, reason: String) {
        updatePipelineSnapshot { current ->
            current.copy(
                fallback = if (active || reason.isNotBlank()) {
                    FallbackState(current.generation, active, reason)
                } else null
            )
        }
        fallbackUsed.set(active)
        fallbackReason.set(reason)
    }

    fun publishPhonePreview(generation: Long, active: Boolean, failureReason: String = "") {
        updatePipelineSnapshot { current ->
            if (current.generation != generation) return@updatePipelineSnapshot current
            current.copy(
                phonePreviewActive = current.desired.localPreviewEnabled && active,
                phonePreviewFailureReason = if (!current.desired.localPreviewEnabled || active) "" else failureReason
            )
        }
    }

    private fun mirrorLegacyConfig(config: StreamConfig) {
        accessMode.set(config.accessMode)
        port.set(config.port)
        accessToken.set(config.accessToken)
        streamMode.set(config.streamMode)
        h264Bitrate.set(config.h264Bitrate)
        h264KeyframeInterval.set(config.h264KeyframeInterval)
        cameraId.set(config.cameraId)
        width.set(config.width)
        height.set(config.height)
        outputWidth.set(config.outputWidth)
        outputHeight.set(config.outputHeight)
        profile.set(config.profile)
        jpegQuality.set(config.jpegQuality)
        fps.set(config.fps)
        previewFitMode.set(config.previewFitMode)
        aspectRatio.set(config.aspectRatio)
        zoomSpeed.set(config.zoomSpeed)
        displayRotation.set(config.displayRotation)
        mirror.set(config.mirror)
        localPreviewEnabled.set(config.localPreviewEnabled)
        targetBandwidthMbps.set(config.targetBandwidthMbps)
    }

    fun refreshConfigSnapshotFromLegacy() = publishConfig(
        StreamConfig(
            accessMode = accessMode.get(),
            port = port.get(),
            accessToken = accessToken.get(),
            streamMode = streamMode.get(),
            h264Bitrate = h264Bitrate.get(),
            h264KeyframeInterval = h264KeyframeInterval.get(),
            cameraId = cameraId.get(),
            width = width.get(),
            height = height.get(),
            outputWidth = outputWidth.get(),
            outputHeight = outputHeight.get(),
            profile = profile.get(),
            jpegQuality = jpegQuality.get(),
            fps = fps.get(),
            previewFitMode = previewFitMode.get(),
            aspectRatio = aspectRatio.get(),
            zoomSpeed = zoomSpeed.get(),
            displayRotation = displayRotation.get(),
            mirror = mirror.get(),
            localPreviewEnabled = localPreviewEnabled.get(),
            targetBandwidthMbps = targetBandwidthMbps.get()
        )
    )

    fun toStatusDto(): StreamStatusDto {
        val snapshot = pipelineSnapshot.get()
        val config = snapshot.desired
        val selectedState = snapshot.selected?.takeIf { it.generation == snapshot.generation }
        val actualState = snapshot.actual?.takeIf { it.generation == snapshot.generation }
        val fallbackState = snapshot.fallback?.takeIf { it.generation == snapshot.generation }
        val selected = selectedState?.let {
            SelectedPipelineDto(
                generation = it.generation,
                cameraId = it.cameraId,
                streamMode = it.streamMode,
                captureEngine = it.captureEngine,
                width = it.width,
                height = it.height,
                fps = it.fps,
                encoderName = it.encoderName,
                hardwareEncoder = it.hardwareEncoder
            )
        }
        val actual = actualState?.let {
            ActualPipelineDto(
                generation = it.generation,
                width = it.width,
                height = it.height,
                captureFps = it.captureFps,
                encodedFps = it.encodedFps,
                encodedBitrate = it.encodedBitrate
            )
        }
        val publicSnapshot = PipelineSnapshotDto(
            revision = snapshot.revision,
            generation = snapshot.generation,
            lifecycleState = snapshot.lifecycle.name,
            desired = DesiredPipelineDto(
                cameraId = config.cameraId,
                streamMode = config.streamMode,
                width = config.width,
                height = config.height,
                fps = config.fps,
                h264Bitrate = config.h264Bitrate,
                phonePreviewEnabled = config.localPreviewEnabled
            ),
            selected = selected,
            actual = actual,
            fallback = fallbackState?.let { FallbackStateDto(it.generation, it.active, it.reason) },
            phonePreviewRequested = config.localPreviewEnabled,
            phonePreviewActive = snapshot.phonePreviewActive,
            phonePreviewFailureReason = snapshot.phonePreviewFailureReason,
            lastRequestId = snapshot.lastRequestId,
            lastUpdatedBy = snapshot.lastUpdatedBy
        )
        return StreamStatusDto(
        revision = snapshot.revision,
        pipelineGeneration = snapshot.generation,
        updatedAtMillis = snapshot.updatedAtMillis,
        lastUpdatedBy = snapshot.lastUpdatedBy,
        lastRequestId = snapshot.lastRequestId,
        snapshot = publicSnapshot,
        streaming = snapshot.lifecycle == LifecycleState.STREAMING,
        lifecycleState = snapshot.lifecycle.name,
        latestFrameRevision = latestFrameRevision.get(),
        lastError = lastError.get(),
        accessMode = config.accessMode,
        port = config.port,
        tokenRequired = config.accessMode == "lanToken",
        allowLan = config.accessMode != "usbOnly",
        streamMode = config.streamMode,
        activeStreamMode = selectedState?.streamMode ?: config.streamMode,
        fallbackReason = fallbackState?.reason.orEmpty(),
        h264Bitrate = config.h264Bitrate,
        h264KeyframeInterval = config.h264KeyframeInterval,
        cameraId = config.cameraId,
        width = config.width,
        height = config.height,
        outputWidth = config.outputWidth,
        outputHeight = config.outputHeight,
        profile = config.profile,
        fps = config.fps,
        jpegQuality = config.jpegQuality,
        previewFitMode = config.previewFitMode,
        aspectRatio = config.aspectRatio,
        zoomSpeed = config.zoomSpeed,
        localPreviewEnabled = config.localPreviewEnabled,
        phonePreviewEnabled = config.localPreviewEnabled,
        phonePreviewRequested = config.localPreviewEnabled,
        phonePreviewActive = snapshot.phonePreviewActive,
        phonePreviewFailureReason = snapshot.phonePreviewFailureReason,
        rebindInProgress = rebindInProgress.get(),
        hasTorch = hasTorch.get(),
        torchEnabled = torchEnabled.get(),
        linearZoom = linearZoom.get(),
        zoomRatio = zoomRatio.get(),
        rotationDegrees = if (selectedState != null) rotationDegrees.get() else 0,
        sensorOrientation = if (selectedState != null) sensorOrientation.get() else 0,
        frameWidth = actualState?.width ?: selectedState?.width ?: 0,
        frameHeight = actualState?.height ?: selectedState?.height ?: 0,
        encodedWidth = actualState?.width ?: selectedState?.width ?: 0,
        encodedHeight = actualState?.height ?: selectedState?.height ?: 0,
        rotationApplied = selectedState != null && rotationApplied.get(),
        targetBandwidthMbps = config.targetBandwidthMbps,
        estimatedMbps = estimatedMbps.get(),
        isFramePortrait = frameHeight.get() > frameWidth.get(),
        isFrameLandscape = frameWidth.get() >= frameHeight.get(),
        displayRotation = config.displayRotation,
        mirror = config.mirror,
        requestedAspectRatio = requestedAspectRatio.get(),
        selectedAspectRatio = selectedAspectRatio.get(),
        aspectRatioMatch = aspectRatioMatch.get(),
        resizeNeeded = resizeNeeded.get(),
        selectedRawWidth = selectedState?.width ?: 0,
        selectedRawHeight = selectedState?.height ?: 0,
        selectedEffectiveWidth = selectedState?.width ?: 0,
        selectedEffectiveHeight = selectedState?.height ?: 0,
        normalizedForPolicy = normalizedForPolicy.get(),
        resolutionPolicy = resolutionPolicy.get(),
        fallbackUsed = fallbackState?.active == true,
        mjpegClients = mjpegClientCount.get(),
        h264Clients = h264ClientCount.get(),
        yuvMsAvg = yuvMsAvg.get(),
        jpegMsAvg = jpegMsAvg.get(),
        rotateMsAvg = rotateMsAvg.get(),
        captureFps = actualState?.captureFps ?: 0,
        encodedFps = actualState?.encodedFps ?: 0,
        encodedBitrate = actualState?.encodedBitrate ?: 0,
        encoderName = selectedState?.encoderName.orEmpty(),
        hardwareEncoder = selectedState?.hardwareEncoder == true,
        captureEngine = selectedState?.captureEngine.orEmpty(),
        cameraSessionFps = cameraSessionFps.get(),
        gpuBridgeFps = gpuBridgeFps.get(),
        capturePathError = capturePathError.get()
        )
    }
}

@Serializable
data class StreamStatusDto(
    val revision: Long,
    val pipelineGeneration: Long,
    val updatedAtMillis: Long,
    val lastUpdatedBy: String,
    val lastRequestId: String? = null,
    val snapshot: PipelineSnapshotDto,
    val streaming: Boolean,
    val lifecycleState: String,
    val latestFrameRevision: Long = 0L,
    val lastError: String,
    val accessMode: String,
    val port: Int,
    val tokenRequired: Boolean,
    val allowLan: Boolean,
    val streamMode: String,
    val activeStreamMode: String = "mjpeg",
    val fallbackReason: String = "",
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
    val phonePreviewEnabled: Boolean = localPreviewEnabled,
    val phonePreviewRequested: Boolean = phonePreviewEnabled,
    val phonePreviewActive: Boolean = false,
    val phonePreviewFailureReason: String = "",
    val rebindInProgress: Boolean,
    val hasTorch: Boolean,
    val torchEnabled: Boolean = false,
    val linearZoom: Float = 0f,
    val zoomRatio: Float = 1f,
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
    val fallbackUsed: Boolean = false,
    val mjpegClients: Int = 0,
    val h264Clients: Int = 0,
    val yuvMsAvg: Double = 0.0,
    val jpegMsAvg: Double = 0.0,
    val rotateMsAvg: Double = 0.0,
    val captureFps: Int = 0,
    val encodedFps: Int = 0,
    val encodedBitrate: Int = 0,
    val encoderName: String = "",
    val hardwareEncoder: Boolean = false,
    val captureEngine: String = "",
    val cameraSessionFps: Int = 0,
    val gpuBridgeFps: Int = 0,
    val capturePathError: String = ""
)

@Serializable
data class PipelineSnapshotDto(
    val revision: Long,
    val generation: Long,
    val lifecycleState: String,
    val desired: DesiredPipelineDto,
    val selected: SelectedPipelineDto?,
    val actual: ActualPipelineDto?,
    val fallback: FallbackStateDto?,
    val phonePreviewRequested: Boolean,
    val phonePreviewActive: Boolean,
    val phonePreviewFailureReason: String,
    val lastRequestId: String?,
    val lastUpdatedBy: String
)

@Serializable
data class DesiredPipelineDto(
    val cameraId: String,
    val streamMode: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val h264Bitrate: Int,
    val phonePreviewEnabled: Boolean
)

@Serializable
data class SelectedPipelineDto(
    val generation: Long,
    val cameraId: String,
    val streamMode: String,
    val captureEngine: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val encoderName: String?,
    val hardwareEncoder: Boolean
)

@Serializable
data class ActualPipelineDto(
    val generation: Long,
    val width: Int,
    val height: Int,
    val captureFps: Int,
    val encodedFps: Int,
    val encodedBitrate: Int
)

@Serializable
data class FallbackStateDto(
    val generation: Long,
    val active: Boolean,
    val reason: String
)
