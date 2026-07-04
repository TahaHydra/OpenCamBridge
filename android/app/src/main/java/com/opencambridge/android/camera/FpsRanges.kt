package com.opencambridge.android.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import kotlin.math.abs

/**
 * Chooses a supported AE target FPS range for the camera that will be bound,
 * instead of hardcoding ranges like [30,30] that do not exist on every device
 * (requesting an unsupported range is ignored at best and rejected at worst).
 */
object FpsRanges {

    fun choose(context: Context, requestedCameraId: String, targetFps: Int): Range<Int>? {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            if (ids.isEmpty()) return null

            val id = when {
                ids.contains(requestedCameraId) -> requestedCameraId
                else -> ids.firstOrNull { cid ->
                    try {
                        manager.getCameraCharacteristics(cid)
                            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    } catch (e: Exception) {
                        false
                    }
                } ?: ids.first()
            }

            val ranges = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            // Prefer a range that contains the target, with the upper bound as
            // close to the target as possible (avoids running the sensor faster
            // than needed) and the narrowest span (fixed-rate ranges give the
            // steadiest cadence). If nothing contains the target (e.g. 60 fps
            // requested on a 30 fps sensor), take the closest available range.
            val containing = ranges.filter { targetFps >= it.lower && targetFps <= it.upper }
            containing.minWithOrNull(
                compareBy({ it.upper - targetFps }, { it.upper - it.lower })
            ) ?: ranges.minByOrNull { abs(it.upper - targetFps) * 2 + abs(it.lower - targetFps) }
        } catch (e: Exception) {
            null
        }
    }
}
