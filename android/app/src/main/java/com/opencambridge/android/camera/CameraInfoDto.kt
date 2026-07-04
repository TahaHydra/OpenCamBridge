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
    val zoomRatioMax: Float = 1.0f
)

@Serializable
data class SizeDto(val width: Int, val height: Int)

@Serializable
data class FpsRangeDto(val min: Int, val max: Int)
