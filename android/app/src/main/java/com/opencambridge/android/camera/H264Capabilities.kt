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

data class H264EncoderSelection(
    val codecName: String,
    val hardware: Boolean,
    val mode: H264ModeDto,
    val bitrateRange: Range<Int>
)

/** Intersects Camera2 surface capabilities with real hardware AVC encoders. */
object H264Capabilities {
    private val preferred = listOf(
        H264ModeDto(1920, 1080, 60),
        H264ModeDto(1280, 720, 60),
        H264ModeDto(1920, 1080, 30),
        H264ModeDto(1280, 720, 30)
    )

    fun supportedModes(context: Context, cameraId: String): List<H264ModeDto> =
        preferred.filter { findEncoder(context, cameraId, it) != null }

    fun select(
        context: Context,
        cameraId: String,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int
    ): H264EncoderSelection? {
        val requested = H264ModeDto(requestedWidth, requestedHeight, requestedFps)
        findEncoder(context, cameraId, requested)?.let { return it }
        for (mode in preferred) findEncoder(context, cameraId, mode)?.let { return it }
        return null
    }

    private fun findEncoder(
        context: Context,
        cameraId: String,
        mode: H264ModeDto
    ): H264EncoderSelection? {
        if (!cameraSupports(context, cameraId, mode)) return null
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isSoftwareOnly }
            .filter { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .sortedBy { it.name }

        for (info in infos) {
            val caps = try { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (_: Exception) { continue }
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val video = caps.videoCapabilities ?: continue
            val supported = try {
                video.areSizeAndRateSupported(mode.width, mode.height, mode.fps.toDouble())
            } catch (_: Exception) {
                false
            }
            if (supported) {
                return H264EncoderSelection(info.name, true, mode, video.bitrateRange)
            }
        }
        return null
    }

    private fun cameraSupports(context: Context, cameraId: String, mode: H264ModeDto): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = try { manager.getCameraCharacteristics(cameraId) } catch (_: Exception) { return false }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val size = Size(mode.width, mode.height)
        val outputSizes = try { map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        if (outputSizes.none { it == size }) return false

        val aeRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        if (aeRanges.none { it.lower <= mode.fps && it.upper >= mode.fps }) return false

        val minDuration = try { map.getOutputMinFrameDuration(MediaCodec::class.java, size) } catch (_: Exception) { 0L }
        return minDuration <= 0L || (1_000_000_000.0 / minDuration) + 0.5 >= mode.fps
    }
}
