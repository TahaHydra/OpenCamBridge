package com.opencambridge.android.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Uses Camera2 CameraManager to enumerate physical cameras and read their
 * characteristics. Extracts sizes, fps ranges, and generates a descriptive label.
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
            
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            
            val hwLevelInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val hardwareLevel = when (hwLevelInt) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }

            // Get supported sizes for YUV_420_888 (since we use it for ImageAnalysis)
            val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { SizeDto(it.width, it.height) }
                ?.sortedByDescending { it.width * it.height }
                ?: emptyList()

            // Get supported FPS ranges
            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { FpsRangeDto(it.lower, it.upper) }
                ?.sortedByDescending { it.max }
                ?: emptyList()

            // Generate a best-effort label
            val label = buildLabel(facing, focalLengths, id)

            CameraInfoDto(
                id = id,
                facing = facing,
                focalLengths = focalLengths,
                apertures = apertures,
                sensorOrientation = sensorOrientation,
                hardwareLevel = hardwareLevel,
                supportedSizes = sizes,
                supportedFpsRanges = fpsRanges,
                label = label
            )
        }
    }

    private fun buildLabel(facing: String, focalLengths: List<Float>, id: String): String {
        val base = facing.replaceFirstChar { it.uppercase() }
        if (facing != "back" || focalLengths.isEmpty()) {
            return "$base Camera $id"
        }
        
        // Very rough heuristic for focal lengths on mobile phones:
        // < 3mm is usually ultrawide
        // 3mm - 5mm is usually standard wide
        // > 5mm is usually telephoto
        val fl = focalLengths.first()
        val suffix = when {
            fl < 3.0f -> "ultrawide"
            fl > 5.0f -> "telephoto"
            else -> "wide"
        }
        return "$base $suffix ($id)"
    }
}
