package com.opencambridge.android.camera

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface

/** Authoritative transform carried beside raw Camera2/MediaCodec H.264. */
data class FrameTransform(
    val effectiveRotation: Int,
    val mirror: Boolean,
    val sensorOrientation: Int,
    val deviceRotation: Int
)

object FrameTransformPolicy {
    fun surfaceRotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    fun calculate(
        sensorOrientation: Int,
        deviceRotation: Int,
        lensFacing: Int?,
        manualRotation: String,
        mirror: Boolean
    ): FrameTransform {
        val sensor = normalize(sensorOrientation)
        val device = normalize(deviceRotation)
        // Camera2 surfaces contain sensor-oriented pixels. Front-facing sensor
        // coordinates rotate in the opposite display direction from back-facing
        // coordinates; mirroring is deliberately kept as a separate operation.
        val autoRotation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            normalize(sensor + device)
        } else {
            normalize(sensor - device)
        }
        val manual = manualRotation.toIntOrNull()?.takeIf { it in setOf(0, 90, 180, 270) } ?: 0
        return FrameTransform(normalize(autoRotation + manual), mirror, sensor, device)
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
