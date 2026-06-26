package com.opencambridge.android.camera

import kotlinx.serialization.Serializable

@Serializable
data class CameraInfoDto(
    val id: String,
    val facing: String,          // "back", "front", "external", "unknown"
    val focalLengths: List<Float> = emptyList(),
    val apertures: List<Float> = emptyList()
)
