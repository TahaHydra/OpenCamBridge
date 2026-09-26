package com.opencambridge.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencambridge.android.StreamViewModel
import com.opencambridge.android.state.H264SettingsPolicy
import com.opencambridge.android.state.LogEntry
import com.opencambridge.android.ui.MonoFamily
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.PhoneUiViewModel
import com.opencambridge.android.ui.components.NavRow
import com.opencambridge.android.ui.components.Notice
import com.opencambridge.android.ui.components.RowDivider
import com.opencambridge.android.ui.components.ScreenHeader
import com.opencambridge.android.ui.components.SegmentedControl
import com.opencambridge.android.ui.components.SegmentedRow
import com.opencambridge.android.ui.components.SettingsGroup
import com.opencambridge.android.ui.components.SliderRow
import com.opencambridge.android.ui.components.Tone
import com.opencambridge.android.ui.components.ValueRow
import com.opencambridge.android.ui.engineLabel
import com.opencambridge.android.ui.formatMbps
import com.opencambridge.android.ui.shortCodec
import com.opencambridge.android.ui.surfaceRotationDegrees
import kotlin.math.roundToInt

@Composable
fun AdvancedSettingsPage(
    viewModel: StreamViewModel,
    ui: PhoneUiViewModel,
    onNavigate: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    val running by ui.serviceRunning.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val problems = logs.count { it.level == "ERROR" || it.level == "WARN" }

    SettingsScaffold(title = "Advanced", onBack = onBack) {
        if (running) {
            EncodingGroup(viewModel)
            PreviewShapeGroup(viewModel)
        } else {
            Notice("Encoding and preview-shape settings appear here while OpenCamBridge is running.", Tone.Accent, modifier = Modifier.fillMaxWidth())
        }
        SettingsGroup(title = "Troubleshooting") {
            NavRow("Diagnostics", onClick = { onNavigate(SettingsPage.Diagnostics) }, subtitle = "Pipeline, encoder and orientation details", icon = Icons.Filled.Insights)
            RowDivider()
            NavRow(
                "Logs",
                onClick = { onNavigate(SettingsPage.Logs) },
                subtitle = if (problems > 0) "$problems warning${if (problems == 1) "" else "s"} or error${if (problems == 1) "" else "s"}" else "No problems recorded",
                icon = Icons.AutoMirrored.Filled.List
            )
        }
    }
}

@Composable
private fun EncodingGroup(viewModel: StreamViewModel) {
    val streamMode by viewModel.streamMode.collectAsState()
    val bitrate by viewModel.h264Bitrate.collectAsState()
    val keyframeInterval by viewModel.h264KeyframeInterval.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()
    val encoderName by viewModel.encoderName.collectAsState()
    val hardwareEncoder by viewModel.hardwareEncoder.collectAsState()
    val encodedBitrate by viewModel.encodedBitrate.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()
    val enabled = !rebinding

    SettingsGroup(title = "Encoding") {
        if (streamMode == "h264") {
            var mbps by remember { mutableFloatStateOf((bitrate / 1_000_000).toFloat()) }
            LaunchedEffect(bitrate) { mbps = (bitrate / 1_000_000).toFloat() }
            SliderRow(
                title = "Bitrate",
                readout = "${mbps.roundToInt()} Mb/s",
                value = mbps,
                range = 1f..20f,
                steps = 18,
                enabled = enabled,
                onChange = { mbps = it },
                onCommit = { viewModel.updateH264Bitrate(mbps.roundToInt() * 1_000_000) },
                subtitle = "Applies live. Very low values are raised to a floor for the resolution."
            )
            RowDivider()
            var seconds by remember { mutableFloatStateOf(keyframeInterval.toFloat()) }
            LaunchedEffect(keyframeInterval) { seconds = keyframeInterval.toFloat() }
            SliderRow(
                title = "Keyframe interval",
                readout = "${seconds.roundToInt()} s",
                value = seconds,
                range = H264SettingsPolicy.MIN_KEYFRAME_INTERVAL_SECONDS.toFloat()..H264SettingsPolicy.MAX_KEYFRAME_INTERVAL_SECONDS.toFloat(),
                steps = H264SettingsPolicy.MAX_KEYFRAME_INTERVAL_SECONDS - H264SettingsPolicy.MIN_KEYFRAME_INTERVAL_SECONDS - 1,
                enabled = enabled,
                onChange = { seconds = it },
                onCommit = { viewModel.updateH264KeyframeInterval(seconds.roundToInt()) },
                subtitle = "A safety net — keyframes are also sent whenever a computer connects."
            )
            if (encoderName.isNotBlank()) {
                RowDivider()
                ValueRow("Encoder", "${shortCodec(encoderName)} · ${if (hardwareEncoder) "hardware" else "software"}", tone = if (hardwareEncoder) null else Tone.Warn)
                if (encodedBitrate > 0) {
                    RowDivider()
                    ValueRow("Measured", formatMbps(encodedBitrate, 2), mono = true)
                }
            }
        } else {
            var quality by remember { mutableFloatStateOf(jpegQuality.toFloat()) }
            LaunchedEffect(jpegQuality) { quality = jpegQuality.toFloat() }
            SliderRow(
                title = "JPEG quality",
                readout = "${quality.roundToInt()}%",
                value = quality,
                range = 10f..100f,
                steps = 89,
                enabled = enabled,
                onChange = { quality = it },
                onCommit = { viewModel.updateJpegQuality(quality.roundToInt()) },
                subtitle = "Every MJPEG frame is a full picture, so quality costs bandwidth on every frame."
            )
        }
    }
}

@Composable
private fun PreviewShapeGroup(viewModel: StreamViewModel) {
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val fitMode by viewModel.previewFitMode.collectAsState()
    val zoomSpeed by viewModel.zoomSpeed.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()
    SettingsGroup(title = "Previews and controls") {
        SegmentedRow(
            title = "Desktop preview shape",
            subtitle = "Pins the preview box in the computer app. This phone always shows the real picture.",
            options = listOf("auto" to "Auto", "16:9" to "Wide", "9:16" to "Tall"),
            selected = aspectRatio,
            enabled = !rebinding,
            onSelect = viewModel::updateAspectRatio
        )
        RowDivider()
        SegmentedRow(
            title = "Web page preview",
            subtitle = "Framing of the preview on this phone's built-in web page.",
            options = listOf("fill" to "Fill", "fit" to "Fit"),
            selected = fitMode,
            enabled = !rebinding,
            onSelect = viewModel::updatePreviewFitMode
        )
        RowDivider()
        SegmentedRow(
            title = "Zoom speed",
            options = listOf("slow" to "Slow", "normal" to "Normal", "fast" to "Fast"),
            selected = zoomSpeed,
            enabled = !rebinding,
            onSelect = viewModel::updateZoomSpeed
        )
    }
}

@Composable
fun DiagnosticsPage(viewModel: StreamViewModel, onBack: () -> Unit) {
    val cameras by viewModel.cameras.collectAsState()
    val selectedCameraId by viewModel.selectedCameraId.collectAsState()
    val width by viewModel.width.collectAsState()
    val height by viewModel.height.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val actualFps by viewModel.actualFps.collectAsState()
    val encodedFps by viewModel.encodedFps.collectAsState()
    val activeMode by viewModel.activeStreamMode.collectAsState()
    val lifecycle by viewModel.lifecycleState.collectAsState()
    val captureEngine by viewModel.captureEngine.collectAsState()
    val encoderName by viewModel.encoderName.collectAsState()
    val hardwareEncoder by viewModel.hardwareEncoder.collectAsState()
    val fallbackReason by viewModel.fallbackReason.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val clients by viewModel.clientCount.collectAsState()
    val sensorOrientation by viewModel.sensorOrientation.collectAsState()
    val deviceSurfaceRotation by viewModel.deviceSurfaceRotation.collectAsState()
    val effectiveRotation by viewModel.rotationDegrees.collectAsState()
    val previewRotation by viewModel.previewRotation.collectAsState()
    val manualRotation by viewModel.displayRotation.collectAsState()
    val encodedWidth by viewModel.encodedWidth.collectAsState()
    val encodedHeight by viewModel.encodedHeight.collectAsState()
    val mirror by viewModel.mirror.collectAsState()

    SettingsScaffold(title = "Diagnostics", onBack = onBack) {
        if (lastError.isNotBlank()) Notice(lastError, Tone.Danger, title = "Last camera error", modifier = Modifier.fillMaxWidth())
        if (fallbackReason.isNotBlank()) Notice(fallbackReason, Tone.Warn, title = "Running on a fallback path", modifier = Modifier.fillMaxWidth())

        SettingsGroup(title = "Pipeline") {
            ValueRow("State", lifecycle.lowercase().replaceFirstChar { it.uppercase() }, tone = if (lifecycle == "STREAMING") Tone.Ok else if (lifecycle == "FAILED") Tone.Danger else Tone.Idle)
            RowDivider()
            ValueRow("Lens", cameras.find { it.id == selectedCameraId }?.label ?: selectedCameraId)
            RowDivider()
            ValueRow("Requested", "${width}×$height @ $fps", mono = true)
            RowDivider()
            ValueRow("Encoded", if (encodedWidth > 0) "${encodedWidth}×$encodedHeight · ${activeMode.uppercase()}" else "—", mono = true)
            RowDivider()
            ValueRow("Capture / encode", "$actualFps / $encodedFps fps", tone = if (encodedFps >= fps - 5) Tone.Ok else Tone.Warn, mono = true)
            RowDivider()
            ValueRow("Capture path", engineLabel(captureEngine))
            RowDivider()
            ValueRow("Encoder", if (encoderName.isBlank()) "—" else "${shortCodec(encoderName)} · ${if (hardwareEncoder) "hardware" else "software"}")
            RowDivider()
            ValueRow("Connected computers", "$clients")
        }

        // The rotation chain, spelled out: effective rotation is the sum of
        // three inputs that are otherwise invisible from the outside.
        SettingsGroup(
            title = "Orientation",
            footer = "The desktop rotates pixels and this phone rotates a view, so their rotations sit a quarter turn apart."
        ) {
            ValueRow("Sensor orientation", "$sensorOrientation°", mono = true)
            RowDivider()
            ValueRow("Phone held at", "${surfaceRotationDegrees(deviceSurfaceRotation)}°", mono = true)
            RowDivider()
            ValueRow("Manual offset", "${manualRotation.toIntOrNull() ?: 0}°", mono = true)
            RowDivider()
            ValueRow("Desktop rotation", "$effectiveRotation°", tone = Tone.Accent, mono = true)
            RowDivider()
            ValueRow("Phone preview rotation", "$previewRotation°", tone = Tone.Accent, mono = true)
            RowDivider()
            ValueRow("Mirror output", if (mirror) "On" else "Off")
            RowDivider()
            val swaps = effectiveRotation == 90 || effectiveRotation == 270
            ValueRow(
                "Encoded → upright",
                "${encodedWidth}×$encodedHeight → " + if (swaps) "${encodedHeight}×$encodedWidth" else "${encodedWidth}×$encodedHeight",
                mono = true
            )
        }
    }
}

@Composable
fun LogsPage(viewModel: StreamViewModel, onBack: () -> Unit) {
    val logs by viewModel.logs.collectAsState()
    var problemsOnly by remember { mutableStateOf(true) }
    val visible = if (problemsOnly) logs.filter { it.level == "ERROR" || it.level == "WARN" } else logs

    Column(modifier = Modifier.fillMaxSize().background(Ocb.Bg)) {
        ScreenHeader(title = "Logs", onBack = onBack, trailing = {
            Text(
                "Clear",
                style = MaterialTheme.typography.labelLarge,
                color = Ocb.AccentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = viewModel::clearLogs)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        })
        SegmentedControl(
            options = listOf(true to "Problems", false to "Everything"),
            selected = problemsOnly,
            onSelect = { problemsOnly = it },
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (visible.isEmpty()) {
            Text(
                if (problemsOnly) "No warnings or errors." else "No log entries yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ocb.Text3,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible) { entry -> LogLine(entry) }
            }
        }
    }
}

@Composable
private fun LogLine(log: LogEntry) {
    val tone = when (log.level) {
        "ERROR" -> Ocb.DangerText
        "WARN" -> Ocb.Warn
        else -> Ocb.Text2
    }
    val time = remember(log.timestamp) {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(log.timestamp))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(time, fontFamily = MonoFamily, fontSize = 11.sp, color = Ocb.Text4)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(log.source.uppercase(), style = MaterialTheme.typography.labelSmall, color = tone.copy(alpha = 0.8f))
            Text(
                log.message,
                fontFamily = MonoFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = tone,
                fontWeight = if (log.level == "ERROR") FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
