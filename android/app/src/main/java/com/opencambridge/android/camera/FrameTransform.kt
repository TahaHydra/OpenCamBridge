package com.opencambridge.android.camera

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface

/**
 * Authoritative transform carried beside raw Camera2/MediaCodec H.264.
 *
 * There are two consumers of rotation and they need different numbers, which is
 * the source of every orientation bug this file has had:
 *
 *  - The Windows producer rotates PIXEL DATA and needs the full correction
 *    ([effectiveRotation]).
 *  - This phone's own `TextureView` gets an already-corrected picture from the
 *    camera surface, so it needs only what the surface has NOT applied, which is
 *    the user's manual offset ([previewRotation]).
 *
 * [autoRotation] is the sensor/device part alone, which is what determines the
 * shape of the picture the surface hands over.
 */
data class FrameTransform(
    /**
     * Rotation the CONSUMER applies: sensor/device correction plus the user's
     * manual offset. This is what travels in OCB2 stream-info and what the
     * Windows producer uses.
     */
    val effectiveRotation: Int,
    /**
     * The sensor/device correction alone, without the manual offset.
     *
     * The camera surface presents a picture already corrected by this much, so it
     * is what decides whether that picture is landscape or portrait — and hence
     * the aspect the phone's preview transform must reason in.
     */
    val autoRotation: Int,
    /**
     * Rotation this phone's own `TextureView` applies: [effectiveRotation] less one
     * quarter turn, because the camera surface has already contributed that much.
     *
     * Verified on device in both orientations; do not "simplify" it to the manual
     * offset alone. That was tried and it flipped the picture — the surface applies
     * the SENSOR correction, not the sensor/device correction, so the device part
     * still has to be done here.
     */
    val previewRotation: Int,
    val mirror: Boolean,
    val sensorOrientation: Int,
    val deviceRotation: Int
)

object FrameTransformPolicy {
    /**
     * Quarter turn the camera surface has already contributed.
     *
     * TextureView applies the SurfaceTexture transform before ours, and for a
     * Camera2 source that transform carries the SENSOR orientation. So the local
     * preview needs the effective rotation less this much.
     */
    const val LOCAL_PREVIEW_OFFSET_DEGREES = -90

    fun localPreviewRotation(consumerRotation: Int): Int =
        normalize(consumerRotation + LOCAL_PREVIEW_OFFSET_DEGREES)

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
        val auto = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            normalize(sensor + device)
        } else {
            normalize(sensor - device)
        }
        val manual = manualRotation.toIntOrNull()?.takeIf { it in setOf(0, 90, 180, 270) } ?: 0
        return FrameTransform(
            effectiveRotation = normalize(auto + manual),
            autoRotation = auto,
            previewRotation = localPreviewRotation(normalize(auto + manual)),
            mirror = mirror,
            sensorOrientation = sensor,
            deviceRotation = device
        )
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
