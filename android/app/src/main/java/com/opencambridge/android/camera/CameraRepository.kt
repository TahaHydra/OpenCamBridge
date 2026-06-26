package com.opencambridge.android.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Uses Camera2 CameraManager to enumerate physical cameras and read their
 * characteristics. This is the lightest way to get focal length / aperture
 * without opening a camera session.
 */
class CameraRepository(private val context: Context) {

    fun listCameras(): List<CameraInfoDto> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.map { id ->
            val chars = manager.getCameraCharacteristics(id)
            val facingInt = chars.get(CameraCharacteristics.LENS_FACING)
            val facing = when (facingInt) {
                CameraCharacteristics.LENS_FACING_BACK     -> "back"
                CameraCharacteristics.LENS_FACING_FRONT    -> "front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else                                       -> "unknown"
            }
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList() ?: emptyList()
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.toList() ?: emptyList()
            CameraInfoDto(
                id = id,
                facing = facing,
                focalLengths = focalLengths,
                apertures = apertures
            )
        }
    }
}
