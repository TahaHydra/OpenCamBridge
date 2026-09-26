package com.opencambridge.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.opencambridge.android.StreamViewModel
import com.opencambridge.android.ui.PhoneUiViewModel
import com.opencambridge.android.ui.components.DropdownRow
import com.opencambridge.android.ui.components.Notice
import com.opencambridge.android.ui.components.RowDivider
import com.opencambridge.android.ui.components.SegmentedRow
import com.opencambridge.android.ui.components.SettingsGroup
import com.opencambridge.android.ui.components.SwitchRow
import com.opencambridge.android.ui.components.Tone
import com.opencambridge.android.ui.components.ValueRow
import com.opencambridge.android.ui.describeFormat
import com.opencambridge.android.ui.describeHeight
import com.opencambridge.android.ui.screens.CaptureActions

@Composable
fun CameraSettingsPage(viewModel: StreamViewModel, ui: PhoneUiViewModel, onBack: () -> Unit) {
    val running by ui.serviceRunning.collectAsState()
    SettingsScaffold(title = "Camera & quality", onBack = onBack) {
        if (running) LiveCameraSettings(viewModel) else StoppedCameraSettings(viewModel, ui)
    }
}

@Composable
private fun LiveCameraSettings(viewModel: StreamViewModel) {
    val cameras by viewModel.cameras.collectAsState()
    val cameraId by viewModel.selectedCameraId.collectAsState()
    val streamMode by viewModel.streamMode.collectAsState()
    val width by viewModel.width.collectAsState()
    val height by viewModel.height.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val mirror by viewModel.mirror.collectAsState()
    val displayRotation by viewModel.displayRotation.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()
    val actualFps by viewModel.actualFps.collectAsState()

    val enabled = !rebinding
    val camera = cameras.find { it.id == cameraId }
    val modes = CaptureActions.modesFor(camera, streamMode)
    val sizes = modes.map { it.width to it.height }.distinct().sortedByDescending { it.first * it.second }
    val rates = modes.filter { it.width == width && it.height == height }.map { it.fps }.distinct().sorted()
    val hasH264 = camera?.h264Modes?.isNotEmpty() ?: true
    val hasMjpeg = camera?.mjpegModes?.isNotEmpty() ?: true

    if (rebinding) {
        Notice("Applying the change — the camera restarts for a moment.", Tone.Busy, modifier = Modifier.fillMaxWidth())
    }

    SettingsGroup(
        title = "Camera",
        footer = if (streamMode == "h264") {
            "Only modes this camera can capture and encode in hardware are offered."
        } else {
            "MJPEG is the compatibility format. Actual rate depends on the camera and the light."
        } + if (actualFps > 0) " Delivering $actualFps of $fps fps now." else ""
    ) {
        DropdownRow(
            title = "Lens",
            options = cameras.map { it.id to it.label },
            selected = cameraId,
            enabled = enabled,
            onSelect = { id ->
                cameras.find { it.id == id }?.let { CaptureActions.switchLens(viewModel, it, streamMode, width, height, fps) }
            }
        )
        RowDivider()
        DropdownRow(
            title = "Resolution",
            options = sizes.map { (w, h) -> (w to h) to "${describeHeight(h)} · ${w}×$h" },
            selected = width to height,
            enabled = enabled,
            emptyText = "No modes",
            onSelect = { (w, h) -> CaptureActions.switchResolution(viewModel, camera, cameraId, streamMode, w, h, fps) }
        )
        RowDivider()
        when {
            rates.size == 1 -> ValueRow("Frame rate", "${rates.first()} fps")
            rates.size in 2..3 -> SegmentedRow(
                title = "Frame rate",
                options = rates.map { it to "$it fps" },
                selected = fps,
                enabled = enabled,
                onSelect = { viewModel.applyCaptureMode(cameraId, streamMode, width, height, it) }
            )
            else -> DropdownRow(
                title = "Frame rate",
                options = rates.map { it to "$it fps" },
                selected = fps,
                enabled = enabled,
                emptyText = "None at this size",
                onSelect = { viewModel.applyCaptureMode(cameraId, streamMode, width, height, it) }
            )
        }
        RowDivider()
        SegmentedRow(
            title = "Format",
            subtitle = "H.264 is hardware-encoded and recommended.",
            options = buildList {
                if (hasH264) add("h264" to "H.264")
                if (hasMjpeg) add("mjpeg" to "MJPEG")
            },
            selected = streamMode,
            enabled = enabled,
            onSelect = { CaptureActions.switchFormat(viewModel, camera, cameraId, it, width, height, fps) }
        )
    }

    SettingsGroup(title = "Picture your computer receives") {
        SwitchRow(
            title = "Mirror camera output",
            subtitle = "Flips the video sent to your computer. Briefly restarts the camera.",
            checked = mirror,
            enabled = enabled,
            onChange = viewModel::updateMirror
        )
        RowDivider()
        SegmentedRow(
            title = "Rotation",
            subtitle = "An extra turn on top of automatic orientation.",
            options = listOf("0" to "0°", "90" to "90°", "180" to "180°", "270" to "270°"),
            selected = (displayRotation.toIntOrNull() ?: 0).toString(),
            enabled = enabled,
            onSelect = viewModel::updateDisplayRotation
        )
    }
}

@Composable
private fun StoppedCameraSettings(viewModel: StreamViewModel, ui: PhoneUiViewModel) {
    val stopped by ui.stoppedSettings.collectAsState()
    val cameras by viewModel.cameras.collectAsState()
    Notice(
        "Press Start to change these. OpenCamBridge will start with the settings below.",
        Tone.Accent,
        modifier = Modifier.fillMaxWidth()
    )
    SettingsGroup(title = "Saved settings") {
        ValueRow("Lens", cameras.find { it.id == stopped.cameraId }?.label ?: "Camera ${stopped.cameraId}")
        RowDivider()
        ValueRow("Resolution", "${describeHeight(stopped.height)} · ${stopped.width}×${stopped.height}")
        RowDivider()
        ValueRow("Frame rate", "${stopped.fps} fps")
        RowDivider()
        ValueRow("Format", describeFormat(stopped.streamMode))
        RowDivider()
        ValueRow("Mirror camera output", if (stopped.mirror) "On" else "Off")
        RowDivider()
        ValueRow("Rotation", "${stopped.displayRotation.toIntOrNull() ?: 0}°")
    }
}
