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
    /** Lens kind: "wide", "ultrawide", "telephoto", or "" when unknown. Lets the
     *  desktop prefer main/back-wide as the default (not telephoto). */
    val lensType: String = "",
    /** Honest max FPS achievable at each standard resolution for this lens,
     *  derived from the sensor's minimum frame duration. maxFps == 0 means the
     *  size is not supported (or the duration is unknown). Used to enable/disable
     *  60 fps in the UI per lens+resolution instead of pretending every phone
     *  can do it. */
    val fpsByResolution: List<ResolutionFpsDto> = emptyList()
)

@Serializable
data class SizeDto(val width: Int, val height: Int)

@Serializable
data class FpsRangeDto(val min: Int, val max: Int)

@Serializable
data class ResolutionFpsDto(val width: Int, val height: Int, val maxFps: Int)
