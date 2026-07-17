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

        // Assign human labels/lens types RELATIVELY (see classifyLenses) rather
        // than from a naive absolute focal-length threshold, which mislabels
        // modern main sensors (e.g. S24 main ~6mm) as "telephoto".
        val labeled = classifyLenses(cameras)

        // Disambiguate duplicate labels by appending the id.
        val labelCounts = labeled.groupingBy { it.label }.eachCount()
        return labeled.map { cam ->
            if ((labelCounts[cam.label] ?: 0) > 1) cam.copy(label = "${cam.label} #${cam.id}") else cam
        }
    }

    /**
     * Labels lenses by comparing focal lengths ACROSS the device's back cameras
     * instead of using absolute thresholds. On phones the ultrawide has a much
     * shorter focal than the main (~0.5x) and a telephoto a much longer one
     * (>=1.4x). We first find the "main" focal (the shortest focal that is not an
     * ultrawide outlier), then classify each back lens relative to it. Anything
     * that does not clearly fit becomes "Back camera N" so a wrong guess never
     * confuses users. Front/external cameras are labeled by facing + id.
     */
    private fun classifyLenses(cameras: List<CameraInfoDto>): List<CameraInfoDto> {
        fun focalOf(c: CameraInfoDto) = c.focalLengths.minOrNull() ?: 0f
        // Monochrome sensors are excluded from the color-lens pool: their focal
        // must not skew the "main" detection, and they must not be labeled
        // ultrawide/telephoto.
        val backs = cameras.filter { it.facing == "back" && !it.isMonochrome && focalOf(it) > 0f }.sortedBy { focalOf(it) }

        // Establish the main (1x) focal length.
        val mainFocal: Float = when {
            backs.isEmpty() -> 0f
            backs.size == 1 -> focalOf(backs[0])
            else -> {
                val smallest = focalOf(backs[0])
                val second = focalOf(backs[1])
                // If the smallest is much wider than the next, it's the ultrawide
                // and the main is the second; otherwise the smallest is the main.
                if (smallest < 0.75f * second) second else smallest
            }
        }

        return cameras.map { cam ->
            when {
                cam.facing == "back" && cam.isMonochrome ->
                    cam.copy(label = "Back monochrome (B&W)", lensType = "mono")
                cam.facing == "back" -> {
                    val fl = focalOf(cam)
                    val (type, name) = when {
                        fl <= 0f || mainFocal <= 0f -> "" to "Back camera ${cam.id}"
                        fl <= 0.75f * mainFocal -> "ultrawide" to "Back ultrawide"
                        fl >= 1.4f * mainFocal -> "telephoto" to "Back telephoto"
                        fl <= 1.15f * mainFocal -> "wide" to "Back main"
                        else -> "" to "Back camera ${cam.id}"
                    }
                    cam.copy(label = name, lensType = type)
                }
                cam.facing == "front" -> cam.copy(label = "Front camera ${cam.id}", lensType = "")
                else -> cam.copy(label = "${cam.facing.replaceFirstChar { it.uppercase() }} camera ${cam.id}", lensType = "")
            }
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

        // Monochrome / near-IR sensors (color filter arrangement MONO=5 or
        // NIR=6, or the MONOCHROME capability) produce a grayscale image. The
        // OnePlus 9 has one; without flagging it, the relative lens classifier
        // can label it "ultrawide" and selecting it shows black & white.
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1
        val monoByCfa = cfa == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
            cfa == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR
        val monoByCap = (chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0))
            .contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME)
        val isMonochrome = monoByCfa || monoByCap

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
        val mjpegModes = fpsByResolution.flatMap { resolution ->
            listOf(15, 30, 60)
                .filter { it <= resolution.maxFps }
                .map { H264ModeDto(resolution.width, resolution.height, it) }
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

        // High-speed Camera2 evidence. MJPEG/ImageAnalysis cannot use these
        // modes, while the H.264 engine may use a direct constrained surface or
        // the GPU surface bridge.
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

        val h264PathCapabilities = H264Capabilities.preferredModes.map { mode ->
            H264ModePathDto(mode, H264Capabilities.inspectPathCapabilities(context, id, mode))
        }
        return CameraInfoDto(
            id = id,
            facing = facing,
            focalLengths = focalLengths,
            apertures = apertures,
            sensorOrientation = sensorOrientation,
            hardwareLevel = hardwareLevel,
            supportedSizes = sizes,
            supportedFpsRanges = fpsRanges,
            // label/lensType are assigned later by classifyLenses (relative).
            label = "Back camera $id",
            zoomRatioMin = zoomMin,
            zoomRatioMax = zoomMax,
            hasTorch = hasTorch,
            lensType = "",
            isMonochrome = isMonochrome,
            fpsByResolution = fpsByResolution,
            mjpegModes = mjpegModes,
            supportsHighSpeed = supportsHighSpeed,
            highSpeedSizes = highSpeedSizes,
            highSpeedFpsRanges = highSpeedFpsRanges,
            h264Modes = h264PathCapabilities.filter { candidate -> candidate.paths.any { it.supported } }.map { it.mode },
            h264PathCapabilities = h264PathCapabilities
        )
    }
}
