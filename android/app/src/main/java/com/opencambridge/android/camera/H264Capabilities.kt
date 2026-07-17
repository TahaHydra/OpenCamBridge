package com.opencambridge.android.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import kotlinx.serialization.Serializable

@Serializable
data class H264ModeDto(val width: Int, val height: Int, val fps: Int)

@Serializable
enum class H264CaptureEngine {
    REGULAR_SURFACE,
    HIGH_SPEED_SURFACE,
    HIGH_SPEED_GPU_BRIDGE
}

@Serializable
data class H264PathCapability(
    val engine: H264CaptureEngine,
    val supported: Boolean,
    val reason: String,
    val cameraFps: Int? = null,
    val aeRangeLower: Int? = null,
    val aeRangeUpper: Int? = null
)

data class H264EncoderSelection(
    val codecName: String,
    val hardware: Boolean,
    val mode: H264ModeDto,
    val bitrateRange: Range<Int>,
    val captureEngine: H264CaptureEngine,
    val cameraFpsRange: Range<Int>,
    val cameraCaptureFps: Int
)

/**
 * Describes the complete public Camera2 surface path rather than inferring a
 * device limit from the regular-session AE ranges alone. A 60 fps mode can be
 * supplied by a regular session, a constrained high-speed encoder surface, or
 * a constrained high-speed SurfaceTexture rendered into the encoder surface.
 */
object H264Capabilities {
    val preferredModes = listOf(
        H264ModeDto(1920, 1080, 60),
        H264ModeDto(1280, 720, 60),
        H264ModeDto(1920, 1080, 30),
        H264ModeDto(1280, 720, 30)
    )

    fun supportedModes(context: Context, cameraId: String): List<H264ModeDto> =
        preferredModes.filter { selectionsForMode(context, cameraId, it).isNotEmpty() }

    fun selectCandidates(
        context: Context,
        cameraId: String,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        includeAdaptiveFallbacks: Boolean = true
    ): List<H264EncoderSelection> {
        val requested = H264ModeDto(requestedWidth, requestedHeight, requestedFps)
        val modes = if (includeAdaptiveFallbacks) {
            CapturePathPolicy.adaptiveModes(requested, preferredModes)
        } else {
            listOf(requested)
        }
        return modes.flatMap { selectionsForMode(context, cameraId, it) }
    }

    fun select(
        context: Context,
        cameraId: String,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int
    ): H264EncoderSelection? = selectCandidates(
        context,
        cameraId,
        requestedWidth,
        requestedHeight,
        requestedFps
    ).firstOrNull()

    fun inspectPathCapabilities(
        context: Context,
        cameraId: String,
        mode: H264ModeDto
    ): List<H264PathCapability> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            return H264CaptureEngine.entries.map {
                H264PathCapability(it, false, "camera characteristics failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return H264CaptureEngine.entries.map { H264PathCapability(it, false, "no stream configuration map") }
        val size = Size(mode.width, mode.height)
        val encoder = findHardwareEncoder(mode)
        val encoderFailure = if (encoder == null) "no hardware AVC surface encoder for ${mode.width}x${mode.height}@${mode.fps}" else null

        val regularSizes = try {
            map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val regularRanges: Array<out Range<Int>> =
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val regularRange = chooseRegularRange(regularRanges, mode.fps)
        val minDuration = if (regularSizes.contains(size)) {
            try { map.getOutputMinFrameDuration(MediaCodec::class.java, size) } catch (_: Exception) { 0L }
        } else {
            0L
        }
        val durationFps = if (minDuration > 0L) 1_000_000_000.0 / minDuration else Double.POSITIVE_INFINITY
        val regularReason = when {
            encoderFailure != null -> encoderFailure
            !regularSizes.contains(size) -> "MediaCodec surface size is absent from regular outputs"
            regularRange == null -> "no regular AE range contains ${mode.fps} fps"
            durationFps + 0.5 < mode.fps -> "regular min frame duration ${minDuration}ns limits output to %.2f fps".format(durationFps)
            else -> "declared by regular Camera2 surface configuration"
        }

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val constrained = capabilities.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
        }
        val highSpeedSizes = if (constrained) {
            try { map.highSpeedVideoSizes.toList() } catch (_: Exception) { emptyList() }
        } else {
            emptyList()
        }
        val highSpeedRanges = if (highSpeedSizes.contains(size)) {
            try { map.getHighSpeedVideoFpsRangesFor(size).toList() } catch (_: Exception) { emptyList() }
        } else {
            emptyList()
        }
        val directRange = chooseDirectHighSpeedRange(highSpeedRanges, mode.fps)
        val bridgeRange = chooseBridgeHighSpeedRange(highSpeedRanges, mode.fps)
        val commonHighSpeedFailure = when {
            encoderFailure != null -> encoderFailure
            !constrained -> "camera does not advertise constrained high-speed video"
            !highSpeedSizes.contains(size) -> "size is absent from constrained high-speed outputs"
            highSpeedRanges.isEmpty() -> "no constrained high-speed FPS ranges for size"
            else -> null
        }
        val directReason = commonHighSpeedFailure ?: if (directRange == null) {
            "no constrained range has upper=${mode.fps} for direct surface capture"
        } else {
            "declared by constrained high-speed direct surface configuration"
        }
        val bridgeReason = commonHighSpeedFailure ?: if (bridgeRange == null) {
            "no constrained rate is an integer multiple of ${mode.fps} for GPU pacing"
        } else if (bridgeRange.upper == mode.fps) {
            "GPU bridge is applicable but direct high-speed surface is preferred at the same rate"
        } else {
            "declared constrained ${bridgeRange.upper} fps input paced to ${mode.fps} fps by GPU"
        }

        return listOf(
            H264PathCapability(
                H264CaptureEngine.REGULAR_SURFACE,
                regularReason.startsWith("declared"),
                regularReason,
                mode.fps,
                regularRange?.lower,
                regularRange?.upper
            ),
            H264PathCapability(
                H264CaptureEngine.HIGH_SPEED_SURFACE,
                directReason.startsWith("declared"),
                directReason,
                directRange?.upper,
                directRange?.lower,
                directRange?.upper
            ),
            H264PathCapability(
                H264CaptureEngine.HIGH_SPEED_GPU_BRIDGE,
                bridgeReason.startsWith("declared"),
                bridgeReason,
                bridgeRange?.upper,
                bridgeRange?.lower,
                bridgeRange?.upper
            )
        )
    }

    private fun selectionsForMode(
        context: Context,
        cameraId: String,
        mode: H264ModeDto
    ): List<H264EncoderSelection> {
        val encoder = findHardwareEncoder(mode) ?: return emptyList()
        val capabilities = inspectPathCapabilities(context, cameraId, mode)
        return capabilities.asSequence()
            .filter { it.supported }
            // Avoid the bridge when the same constrained capture rate can feed
            // the encoder directly. It remains a real candidate whenever a
            // higher camera rate must be decimated to the selected output FPS.
            .filterNot {
                it.engine == H264CaptureEngine.HIGH_SPEED_GPU_BRIDGE &&
                    it.cameraFps == mode.fps &&
                    capabilities.any { direct -> direct.engine == H264CaptureEngine.HIGH_SPEED_SURFACE && direct.supported }
            }
            .map {
                H264EncoderSelection(
                    encoder.first.name,
                    true,
                    mode,
                    encoder.second,
                    it.engine,
                    Range(it.aeRangeLower ?: it.cameraFps ?: mode.fps, it.aeRangeUpper ?: it.cameraFps ?: mode.fps),
                    it.cameraFps ?: mode.fps
                )
            }
            .toList()
    }

    private fun findHardwareEncoder(mode: H264ModeDto): Pair<MediaCodecInfo, Range<Int>>? {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isSoftwareOnly }
            .filter { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .sortedBy { it.name }
        for (info in infos) {
            val caps = try {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (_: Exception) {
                continue
            }
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val video = caps.videoCapabilities ?: continue
            val supported = try {
                video.areSizeAndRateSupported(mode.width, mode.height, mode.fps.toDouble())
            } catch (_: Exception) {
                false
            }
            if (supported) return info to video.bitrateRange
        }
        return null
    }

    private fun chooseRegularRange(ranges: Array<out Range<Int>>, fps: Int): Range<Int>? =
        ranges.filter { it.lower <= fps && it.upper >= fps }
            .minWithOrNull(compareBy<Range<Int>>({ it.upper - it.lower }, { -it.lower }))

    private fun chooseDirectHighSpeedRange(ranges: List<Range<Int>>, fps: Int): Range<Int>? =
        CapturePathPolicy.directRange(ranges.map { it.lower to it.upper }, fps)?.let { Range(it.first, it.second) }

    private fun chooseBridgeHighSpeedRange(ranges: List<Range<Int>>, fps: Int): Range<Int>? =
        CapturePathPolicy.bridgeRange(ranges.map { it.lower to it.upper }, fps)?.let { Range(it.first, it.second) }
}
