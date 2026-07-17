package com.opencambridge.android.camera

import kotlinx.serialization.Serializable

@Serializable
data class CameraInfoDto(
    val id: String,
    val facing: String,
    val focalLengths: List<Float> = emptyList(),
    val apertures: List<Float> = emptyList(),
    val sensorOrientation: Int,
    val hardwareLevel: String?,
    val supportedSizes: List<SizeDto>,
    val supportedFpsRanges: List<FpsRangeDto>,
    val label: String,
    val zoomRatioMin: Float = 1.0f,
    val zoomRatioMax: Float = 1.0f,
    /** Whether this specific lens/camera has a flash unit (torch). Reported per
     *  camera so the UI can hide torch on lenses that do not support it. */
    val hasTorch: Boolean = false,
    /** Lens kind: "wide", "ultrawide", "telephoto", "mono", or "" when unknown.
     *  Lets the desktop prefer main/back-wide as the default (not telephoto). */
    val lensType: String = "",
    /** True for monochrome/near-IR sensors (e.g. the OnePlus 9's mono camera),
     *  which produce a grayscale image. Kept out of the ultrawide/main/telephoto
     *  classification so users don't pick it thinking it's the ultrawide. */
    val isMonochrome: Boolean = false,
    /** Honest max FPS achievable at each standard resolution for this lens,
     *  derived from the sensor's minimum frame duration. maxFps == 0 means the
     *  size is not supported (or the duration is unknown). Used to enable/disable
     *  60 fps in the UI per lens+resolution instead of pretending every phone
     *  can do it. */
    val fpsByResolution: List<ResolutionFpsDto> = emptyList(),

    /** Diagnostics only (NOT used by the MJPEG webcam path). Whether the camera
     *  advertises CONSTRAINED_HIGH_SPEED_VIDEO and the slow-motion sizes/ranges
     *  it exposes there. This explains devices (e.g. some OnePlus/LineageOS
     *  builds) that only offer 30 fps to normal ImageAnalysis yet have 120/240
     *  fps slow-motion modes the standard capture path cannot use. */
    val supportsHighSpeed: Boolean = false,
    val highSpeedSizes: List<SizeDto> = emptyList(),
    val highSpeedFpsRanges: List<FpsRangeDto> = emptyList(),
    /** Modes supported by both this Camera2 surface path and a hardware AVC encoder. */
    val h264Modes: List<H264ModeDto> = emptyList(),
    /** Per-mode public Camera2 path evidence, including exact reasons for every
     * regular/high-speed engine that is unavailable. */
    val h264PathCapabilities: List<H264ModePathDto> = emptyList()
)

@Serializable
data class H264ModePathDto(
    val mode: H264ModeDto,
    val paths: List<H264PathCapability>
)

@Serializable
data class SizeDto(val width: Int, val height: Int)

@Serializable
data class FpsRangeDto(val min: Int, val max: Int)

@Serializable
data class ResolutionFpsDto(val width: Int, val height: Int, val maxFps: Int)
