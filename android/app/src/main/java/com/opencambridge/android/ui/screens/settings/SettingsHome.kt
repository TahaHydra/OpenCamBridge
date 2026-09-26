package com.opencambridge.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencambridge.android.StreamViewModel
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.PhoneUiViewModel
import com.opencambridge.android.ui.components.Notice
import com.opencambridge.android.ui.components.NavRow
import com.opencambridge.android.ui.components.RowDivider
import com.opencambridge.android.ui.components.ScreenHeader
import com.opencambridge.android.ui.components.SettingsGroup
import com.opencambridge.android.ui.components.Tone
import com.opencambridge.android.ui.describeMode

/** Every settings screen reachable from the phone. */
enum class SettingsPage { Home, Camera, Display, Connection, Advanced, Diagnostics, Logs, About }

/** Standard settings screen: header with back, then scrolling sections. */
@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Ocb.Bg)) {
        ScreenHeader(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content
        )
    }
}

@Composable
fun SettingsHome(
    viewModel: StreamViewModel,
    ui: PhoneUiViewModel,
    onNavigate: (SettingsPage) -> Unit,
    onOpenHelp: () -> Unit,
    onBack: () -> Unit,
) {
    val running by ui.serviceRunning.collectAsState()
    val stopped by ui.stoppedSettings.collectAsState()
    val curtain by ui.screenCurtain.collectAsState()
    val cameras by viewModel.cameras.collectAsState()
    val cameraId by viewModel.selectedCameraId.collectAsState()
    val height by viewModel.height.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val streamMode by viewModel.streamMode.collectAsState()
    val liveAccessMode by viewModel.accessMode.collectAsState()
    val livePort by viewModel.port.collectAsState()
    val livePreview by viewModel.localPreviewEnabled.collectAsState()

    val lensId = if (running) cameraId else stopped.cameraId
    val lens = cameras.firstOrNull { it.id == lensId }?.label ?: "Camera $lensId"
    val mode = if (running) describeMode(height, fps, streamMode) else describeMode(stopped.height, stopped.fps, stopped.streamMode)
    val accessMode = if (running) liveAccessMode else stopped.accessMode
    val port = if (running) livePort else stopped.port
    val preview = if (running) livePreview else stopped.localPreviewEnabled

    SettingsScaffold(title = "Settings", onBack = onBack) {
        if (!running) {
            Notice(
                "OpenCamBridge is stopped. Camera settings can be changed after you press Start; connection settings apply the next time it starts.",
                Tone.Accent,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsGroup(title = "Camera") {
            NavRow("Camera & quality", onClick = { onNavigate(SettingsPage.Camera) }, subtitle = "$lens · $mode", icon = Icons.Filled.PhotoCamera)
        }
        SettingsGroup(title = "This phone") {
            NavRow(
                "Display & power",
                onClick = { onNavigate(SettingsPage.Display) },
                subtitle = "Screen curtain: ${curtain.label} · Preview ${if (preview) "on" else "off"}",
                icon = Icons.Filled.Brightness4
            )
            RowDivider()
            NavRow(
                "Connection",
                onClick = { onNavigate(SettingsPage.Connection) },
                subtitle = if (accessMode == "lanToken") "Wi-Fi with access token · port $port" else "USB only · port $port",
                icon = Icons.Filled.Cable
            )
        }
        SettingsGroup(title = "More") {
            NavRow("Advanced", onClick = { onNavigate(SettingsPage.Advanced) }, subtitle = "Encoding, diagnostics and logs", icon = Icons.Filled.Build)
            RowDivider()
            NavRow("Help", onClick = onOpenHelp, subtitle = "How to connect to your computer", icon = Icons.AutoMirrored.Filled.HelpOutline)
            RowDivider()
            NavRow("About", onClick = { onNavigate(SettingsPage.About) }, icon = Icons.Filled.Info)
        }
        Spacer(Modifier.height(8.dp))
    }
}
