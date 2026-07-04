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
        val outputSizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        val sizes = outputSizes
            .map { SizeDto(it.width, it.height) }
            .sortedByDescending { it.width * it.height }

        // Get supported FPS ranges
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { FpsRangeDto(it.lower, it.upper) }
            ?.sortedByDescending { it.max }
            ?: emptyList()

        // Torch availability is per-camera: some lenses (e.g. telephoto,
        // ultrawide, front) have no flash even when the main lens does. Report it
        // honestly so the UI only offers torch where it exists.
        val hasTorch = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        // Honest per-resolution max FPS. The AE target-fps ranges advertise what
        // the sensor *can* do in principle, but the achievable rate at a given
        // capture size is bounded by that size's minimum frame duration. This is
        // the signal the UI uses to decide whether 60 fps is real for a given
        // lens + resolution (instead of assuming every phone can do it).
        val standardSizes = listOf(640 to 480, 960 to 540, 1280 to 720, 1920 to 1080)
        val maxAeFps = fpsRanges.maxOfOrNull { it.max } ?: 30
        val fpsByResolution = standardSizes.mapNotNull { (w, h) ->
            val match = outputSizes.firstOrNull { it.width == w && it.height == h }
                ?: return@mapNotNull null
            val minDurNs = try {
                configMap?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, match) ?: 0L
            } catch (e: Exception) {
                0L
            }
            val durFps = if (minDurNs > 0L) (1_000_000_000.0 / minDurNs).toInt() else maxAeFps
            // The real ceiling is the lower of what the size allows and what the
            // sensor's AE ranges advertise.
            val maxFps = minOf(durFps, maxAeFps).coerceAtLeast(1)
            ResolutionFpsDto(w, h, maxFps)
        }

        // Zoom ratio range (API 30+); older devices only report max digital zoom.
        var zoomMin = 1.0f
        var zoomMax = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                zoomMin = it.lower
                zoomMax = it.upper
            }
        }

        // High-speed (constrained slow-motion) capability — diagnostics only.
        // The normal ImageAnalysis/YUV path cannot use these modes, so a device
        // may report 30 fps max above yet expose 120/240 fps here.
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val supportsHighSpeed = caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
        )
        var highSpeedSizes: List<SizeDto> = emptyList()
        var highSpeedFpsRanges: List<FpsRangeDto> = emptyList()
        if (supportsHighSpeed && configMap != null) {
            try {
                highSpeedSizes = configMap.highSpeedVideoSizes
                    ?.map { SizeDto(it.width, it.height) }
                    ?.sortedByDescending { it.width * it.height } ?: emptyList()
                highSpeedFpsRanges = configMap.highSpeedVideoFpsRanges
                    ?.map { FpsRangeDto(it.lower, it.upper) }
                    ?.distinct()
                    ?.sortedByDescending { it.max } ?: emptyList()
            } catch (e: Exception) {
                Log.w("CameraRepository", "High-speed query failed for $id: ${e.message}")
            }
        }

        val lensType = lensType(facing, focalLengths)
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
            zoomRatioMax = zoomMax,
            hasTorch = hasTorch,
            lensType = lensType,
            fpsByResolution = fpsByResolution,
            supportsHighSpeed = supportsHighSpeed,
            highSpeedSizes = highSpeedSizes,
            highSpeedFpsRanges = highSpeedFpsRanges
        )
    }

    /** Rough lens classification from focal length, back cameras only. */
    private fun lensType(facing: String, focalLengths: List<Float>): String {
        if (facing != "back" || focalLengths.isEmpty()) return ""
        val fl = focalLengths.minOrNull() ?: return ""
        return when {
            fl < 3.0f -> "ultrawide"
            fl > 5.0f -> "telephoto"
            else -> "wide"
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
        val fl = focalLengths.minOrNull() ?: focalLengths.first()
        val suffix = when {
            fl < 3.0f -> "ultrawide"
            fl > 5.0f -> "telephoto"
            else -> "wide"
        }
        return "$base $suffix"
    }
}
