package com.opencambridge.android.ui.screens

import com.opencambridge.android.StreamViewModel
import com.opencambridge.android.camera.CameraInfoDto
import com.opencambridge.android.camera.H264ModeDto

/**
 * Capture changes made from the phone UI.
 *
 * Lens, format, size and rate are validated by the phone as one combination,
 * so each change is sent as one complete mode the target lens really offers —
 * never a half-changed combination the pipeline would have to reject.
 */
internal object CaptureActions {
    fun modesFor(camera: CameraInfoDto?, streamMode: String): List<H264ModeDto> =
        if (camera == null) emptyList() else if (streamMode == "mjpeg") camera.mjpegModes else camera.h264Modes

    /** Nearest supported mode: exact, then same size, then the lens's first mode. */
    private fun pick(modes: List<H264ModeDto>, width: Int, height: Int, fps: Int): H264ModeDto? =
        modes.firstOrNull { it.width == width && it.height == height && it.fps == fps }
            ?: modes.firstOrNull { it.width == width && it.height == height }
            ?: modes.firstOrNull()

    fun switchLens(viewModel: StreamViewModel, camera: CameraInfoDto, streamMode: String, width: Int, height: Int, fps: Int) {
        val mode = pick(modesFor(camera, streamMode), width, height, fps)
        if (mode == null) viewModel.selectCamera(camera.id)
        else viewModel.applyCaptureMode(camera.id, streamMode, mode.width, mode.height, mode.fps)
    }

    fun switchFormat(viewModel: StreamViewModel, camera: CameraInfoDto?, cameraId: String, streamMode: String, width: Int, height: Int, fps: Int) {
        val mode = pick(modesFor(camera, streamMode), width, height, fps)
        if (mode == null) viewModel.updateStreamMode(streamMode)
        else viewModel.applyCaptureMode(cameraId, streamMode, mode.width, mode.height, mode.fps)
    }

    fun switchResolution(viewModel: StreamViewModel, camera: CameraInfoDto?, cameraId: String, streamMode: String, width: Int, height: Int, fps: Int) {
        val candidates = modesFor(camera, streamMode).filter { it.width == width && it.height == height }
        val rate = candidates.firstOrNull { it.fps == fps }?.fps ?: candidates.firstOrNull()?.fps ?: fps
        viewModel.applyCaptureMode(cameraId, streamMode, width, height, rate)
    }
}
