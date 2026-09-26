package com.opencambridge.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencambridge.android.StreamViewModel
import com.opencambridge.android.camera.CameraInfoDto
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.TelemetryStyle
import com.opencambridge.android.ui.components.OcbSlider
import com.opencambridge.android.ui.components.RoundToolButton
import com.opencambridge.android.ui.components.SecondaryButton
import com.opencambridge.android.ui.components.StatusPill
import com.opencambridge.android.ui.components.Tone
import com.opencambridge.android.ui.describeMode
import com.opencambridge.android.ui.preview.LocalPreviewBox
import com.opencambridge.android.ui.preview.previewAspect
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The phone while it is streaming: the picture, whether a computer is
 * receiving it, the three controls people reach for (switch camera, torch,
 * dim) plus zoom, and Stop. Everything else lives in Settings.
 */
@Composable
fun LiveScreen(
    viewModel: StreamViewModel,
    mirrorPreview: Boolean,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onDim: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ocb.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    PreviewArea(viewModel, mirrorPreview)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiveHeader(viewModel, onOpenSettings)
                    LiveControls(viewModel, onStop, onDim)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                LiveHeader(viewModel, onOpenSettings)
                Spacer(Modifier.height(12.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PreviewArea(viewModel, mirrorPreview)
                }
                Spacer(Modifier.height(16.dp))
                LiveControls(viewModel, onStop, onDim)
            }
        }
    }
}

@Composable
private fun LiveHeader(viewModel: StreamViewModel, onOpenSettings: () -> Unit) {
    val clients by viewModel.clientCount.collectAsState()
    val lifecycle by viewModel.lifecycleState.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()
    val accessMode by viewModel.accessMode.collectAsState()
    val (text, tone) = when {
        lifecycle == "RECOVERING" -> "Recovering camera…" to Tone.Busy
        rebinding || lifecycle == "RECONFIGURING" -> "Applying settings…" to Tone.Busy
        clients > 0 -> "Connected" to Tone.Ok
        else -> "Waiting for computer" to Tone.Accent
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(text, tone, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Icon(
                if (accessMode == "lanToken") Icons.Filled.Wifi else Icons.Filled.Usb,
                contentDescription = if (accessMode == "lanToken") "Wi-Fi" else "USB",
                tint = Ocb.Text3,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Ocb.Text2)
        }
    }
}

@Composable
private fun PreviewArea(viewModel: StreamViewModel, mirrorPreview: Boolean) {
    val previewEnabled by viewModel.localPreviewEnabled.collectAsState()
    val previewActive by viewModel.phonePreviewActive.collectAsState()
    val previewFailure by viewModel.phonePreviewFailureReason.collectAsState()
    val rotation by viewModel.rotationDegrees.collectAsState()
    val encodedWidth by viewModel.encodedWidth.collectAsState()
    val encodedHeight by viewModel.encodedHeight.collectAsState()
    val requestedWidth by viewModel.width.collectAsState()
    val requestedHeight by viewModel.height.collectAsState()
    val encodedFps by viewModel.encodedFps.collectAsState()
    val activeMode by viewModel.activeStreamMode.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()

    val aspect = previewAspect(
        outputRotation = rotation,
        width = if (encodedWidth > 0) encodedWidth else requestedWidth,
        height = if (encodedHeight > 0) encodedHeight else requestedHeight
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The largest box of the picture's shape that fits, so the preview is
        // never stretched, cropped or letterboxed inside its own frame.
        val boxWidth = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
        val boxHeight = boxWidth / aspect
        Box(
            modifier = Modifier
                .size(boxWidth, boxHeight)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
        ) {
            if (previewEnabled) {
                // Fill: the box already has the picture's shape, so nothing is cropped.
                LocalPreviewBox("fill", viewModel, mirrorPreview)
            } else {
                PreviewOff(onShow = { viewModel.toggleLocalPreview(true) })
            }
            if (previewEnabled || encodedWidth > 0) {
                Text(
                    if (encodedWidth > 0) describeMode(encodedHeight, encodedFps, activeMode) else "Starting…",
                    style = TelemetryStyle,
                    color = Ocb.Text,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            if (previewEnabled && !previewActive && previewFailure.isNotBlank() && !rebinding) {
                StatusPill(
                    "Preview unavailable",
                    Tone.Warn,
                    onDark = true,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviewOff(onShow: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Preview is off", style = MaterialTheme.typography.titleMedium, color = Ocb.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your computer still receives video. Showing it here briefly restarts the camera.",
            style = MaterialTheme.typography.bodySmall,
            color = Ocb.Text3,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp)
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Ocb.Surface3)
                .clickable(role = Role.Button, onClick = onShow)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Visibility, contentDescription = null, tint = Ocb.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Show preview", style = MaterialTheme.typography.labelLarge, color = Ocb.Text)
        }
    }
}

@Composable
private fun ColumnScope.LiveControls(viewModel: StreamViewModel, onStop: () -> Unit, onDim: () -> Unit) {
    val cameras by viewModel.cameras.collectAsState()
    val selectedCameraId by viewModel.selectedCameraId.collectAsState()
    val streamMode by viewModel.streamMode.collectAsState()
    val width by viewModel.width.collectAsState()
    val height by viewModel.height.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
    val hasTorch by viewModel.hasTorch.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()

    val activeCam = cameras.find { it.id == selectedCameraId }
    // Per-lens capability; falls back to the live flag when the list is absent.
    val torchSupported = activeCam?.hasTorch ?: hasTorch

    LensChip(
        cameras = cameras,
        activeCam = activeCam,
        enabled = !rebinding,
        onSelect = { camera -> CaptureActions.switchLens(viewModel, camera, streamMode, width, height, fps) },
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        RoundToolButton(
            icon = Icons.Filled.Cameraswitch,
            label = "Switch",
            enabled = !rebinding && cameras.size > 1,
            onClick = {
                nextCamera(cameras, activeCam)?.let { CaptureActions.switchLens(viewModel, it, streamMode, width, height, fps) }
            }
        )
        RoundToolButton(
            icon = if (torchEnabled) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
            label = "Torch",
            active = torchEnabled,
            enabled = torchSupported,
            onClick = { viewModel.updateTorch(!torchEnabled) }
        )
        RoundToolButton(icon = Icons.Filled.Bedtime, label = "Dim", onClick = onDim)
    }

    ZoomControl(viewModel, activeCam)

    SecondaryButton(
        text = "Stop",
        onClick = onStop,
        danger = true,
        icon = Icons.Filled.Stop,
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).align(Alignment.CenterHorizontally)
    )
    Text(
        "Stops everything. Start again from this phone.",
        style = MaterialTheme.typography.bodySmall,
        color = Ocb.Text3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    )
}

@Composable
private fun LensChip(
    cameras: List<CameraInfoDto>,
    activeCam: CameraInfoDto?,
    enabled: Boolean,
    onSelect: (CameraInfoDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, Ocb.Border2, RoundedCornerShape(50))
                .clickable(enabled = enabled && cameras.size > 1, role = Role.DropdownList) { expanded = true }
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                activeCam?.label ?: "Camera",
                style = MaterialTheme.typography.labelMedium,
                color = Ocb.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose camera", tint = Ocb.Text3)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Ocb.Surface2)) {
            cameras.forEach { camera ->
                DropdownMenuItem(
                    text = { Text(camera.label, color = if (camera.id == activeCam?.id) Ocb.AccentText else Ocb.Text) },
                    onClick = {
                        expanded = false
                        if (camera.id != activeCam?.id) onSelect(camera)
                    }
                )
            }
        }
    }
}

@Composable
private fun ZoomControl(viewModel: StreamViewModel, activeCam: CameraInfoDto?) {
    val linearZoom by viewModel.linearZoom.collectAsState()
    val zoomMax = activeCam?.zoomRatioMax ?: 1f
    if (activeCam != null && zoomMax <= 1f) return
    // The thumb follows the finger; the phone receives the value once the
    // finger rests, so a drag is not a burst of conflicting zoom requests.
    var local by remember { mutableFloatStateOf(linearZoom) }
    var dragging by remember { mutableStateOf(false) }
    var lastSent by remember { mutableFloatStateOf(linearZoom) }
    val send = { value: Float ->
        if (abs(value - lastSent) > 0.005f) {
            lastSent = value
            viewModel.updateZoom(value)
        }
    }
    LaunchedEffect(linearZoom) { if (!dragging) local = linearZoom }
    LaunchedEffect(local, dragging) {
        if (dragging) {
            delay(120)
            send(local)
        }
    }
    val ratio = activeCam?.let { 1f + local * ((it.zoomRatioMax) - 1f) }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Zoom", style = MaterialTheme.typography.labelMedium, color = Ocb.Text2)
        OcbSlider(
            value = local,
            onValueChange = {
                dragging = true
                local = it
            },
            onValueChangeFinished = {
                dragging = false
                send(local)
            },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Text(
            ratio?.let { String.format(java.util.Locale.US, "%.1f×", it) } ?: "${(local * 100).toInt()}%",
            style = TelemetryStyle,
            color = Ocb.Text2
        )
    }
}

/** Back main ⇄ front, or the next lens when there is no front camera. */
private fun nextCamera(cameras: List<CameraInfoDto>, active: CameraInfoDto?): CameraInfoDto? {
    if (cameras.size < 2) return null
    val mainBack = cameras.firstOrNull { it.facing == "back" && it.lensType == "wide" }
        ?: cameras.firstOrNull { it.facing == "back" }
    val front = cameras.firstOrNull { it.facing == "front" }
    return when {
        active?.facing == "front" && mainBack != null -> mainBack
        active?.facing != "front" && front != null -> front
        else -> cameras[(cameras.indexOf(active) + 1).mod(cameras.size)]
    }
}
