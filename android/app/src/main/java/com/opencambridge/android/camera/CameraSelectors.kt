package com.opencambridge.android.camera

import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector

/**
 * Builds a CameraSelector that targets a specific Camera2 camera id, so every
 * lens the device exposes (main, ultrawide, telephoto, front, external) is
 * actually selectable — not just "front or back".
 *
 * If the requested id is not available to CameraX (stale setting from another
 * device, or a physical lens the vendor hides behind the logical camera), the
 * filter falls back to the full camera list so binding still succeeds with the
 * device default instead of throwing.
 */
object CameraSelectors {

    fun forCameraId(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                val matched = cameraInfos.filter { info ->
                    try {
                        Camera2CameraInfo.from(info).cameraId == cameraId
                    } catch (e: Exception) {
                        false
                    }
                }
                if (matched.isNotEmpty()) {
                    matched
                } else {
                    Log.w(
                        "CameraSelectors",
                        "Camera id '$cameraId' not available to CameraX; falling back to device default"
                    )
                    cameraInfos
                }
            }
            .build()
}
