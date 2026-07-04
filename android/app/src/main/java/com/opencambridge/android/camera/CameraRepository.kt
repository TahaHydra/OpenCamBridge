package com.opencambridge.android.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * Uses Camera2 CameraManager to enumerate cameras and read their
 * characteristics. Extracts sizes, fps ranges, zoom range, and generates a
 * descriptive label (wide / ultrawide / telephoto) so multi-lens phones are
 * usable, not just "front" and "back".
 *
 * Robustness: one camera failing to report characteristics (which happens on
 * some vendor builds for hidden or in-use cameras) must not hide the rest of
 * the list, so each camera is read in isolation.
 */
class CameraRepository(private val context: Context) {

    fun listCameras(): List<CameraInfoDto> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            Log.e("CameraRepository", "Failed to enumerate cameras", e)
            return emptyList()
        }

        val cameras = ids.mapNotNull { id ->
            try {
                readCamera(manager, id)
            } catch (e: Exception) {
                Log.w("CameraRepository", "Skipping camera $id: ${e.message}")
                null
            }
        }

        // Disambiguate duplicate labels ("Back wide") by appending the id.
        val labelCounts = cameras.groupingBy { it.label }.eachCount()
        return cameras.map { cam ->
            if ((labelCounts[cam.label] ?: 0) > 1) cam.copy(label = "${cam.label} #${cam.id}") else cam
        }
    }

    private fun readCamera(manager: CameraManager, id: String): CameraInfoDto {
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

        // Zoom ratio range (API 30+); older devices only report max digital zoom.
        var zoomMin = 1.0f
        var zoomMax = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                zoomMin = it.lower
                zoomMax = it.upper
            }
        }

        val label = buildLabel(facing, focalLengths, id)

        return CameraInfoDto(
            id = id,
            facing = facing,
            focalLengths = focalLengths,
            apertures = apertures,
            sensorOrientation = sensorOrientation,
            hardwareLevel = hardwareLevel,
            supportedSizes = sizes,
            supportedFpsRanges = fpsRanges,
            label = label,
            zoomRatioMin = zoomMin,
            zoomRatioMax = zoomMax
        )
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
        val fl = focalLengths.minOrNull() ?: focalLengths.first()
        val suffix = when {
            fl < 3.0f -> "ultrawide"
            fl > 5.0f -> "telephoto"
            else -> "wide"
        }
        return "$base $suffix"
    }
}
