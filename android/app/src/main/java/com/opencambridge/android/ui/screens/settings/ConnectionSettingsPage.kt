package com.opencambridge.android.ui.screens.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencambridge.android.StreamViewModel
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.PhoneUiViewModel
import com.opencambridge.android.ui.TelemetryStyle
import com.opencambridge.android.ui.components.Notice
import com.opencambridge.android.ui.components.RowDivider
import com.opencambridge.android.ui.components.SecondaryButton
import com.opencambridge.android.ui.components.SegmentedRow
import com.opencambridge.android.ui.components.SettingsGroup
import com.opencambridge.android.ui.components.Tone
import com.opencambridge.android.ui.components.ValueRow
import kotlinx.coroutines.launch

@Composable
fun ConnectionSettingsPage(viewModel: StreamViewModel, ui: PhoneUiViewModel, onBack: () -> Unit) {
    val running by ui.serviceRunning.collectAsState()
    val stopped by ui.stoppedSettings.collectAsState()
    val bound by ui.boundConnection.collectAsState()
    val liveMode by viewModel.accessMode.collectAsState()
    val livePort by viewModel.port.collectAsState()
    val liveToken by viewModel.accessToken.collectAsState()
    val wifiIp by viewModel.wifiIp.collectAsState()
    val clients by viewModel.clientCount.collectAsState()

    val accessMode = if (running) liveMode else stopped.accessMode
    val port = if (running) livePort else stopped.port
    val token = if (running) liveToken else stopped.accessToken
    val wifi = accessMode == "lanToken"
    val pendingRestart = running && bound != null && (bound!!.accessMode != accessMode || bound!!.port != port)

    SettingsScaffold(title = "Connection", onBack = onBack) {
        PairingGroup(running = running, port = if (running) bound?.port ?: port else port, wifi = wifi)
        if (pendingRestart) {
            Notice(
                "Stop and start OpenCamBridge on this phone to switch to ${if (wifi) "Wi-Fi" else "USB only"} on port $port.",
                Tone.Warn,
                title = "Applies at the next start",
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsGroup(
            title = "How your computer connects",
            footer = if (wifi) {
                "Reachable on your network. Pair a PC above or use the manual access token below. Security settings can only be changed on this phone."
            } else {
                "Recommended. Only reachable through the USB cable (adb port forward), so nothing on your network can connect."
            }
        ) {
            SegmentedRow(
                title = "Mode",
                options = listOf("usbOnly" to "USB only", "lanToken" to "Wi-Fi + token"),
                selected = accessMode,
                onSelect = { mode -> if (running) viewModel.updateAccessMode(mode) else ui.setStoppedAccessMode(mode) }
            )
        }

        SettingsGroup(title = "Addresses") {
            if (running) {
                ValueRow("Connected computers", if (clients > 0) "$clients" else "None", tone = if (clients > 0) Tone.Ok else Tone.Idle)
                RowDivider()
            }
            ValueRow("USB", "127.0.0.1:$port", mono = true)
            RowDivider()
            ValueRow(
                "Wi-Fi",
                when {
                    !wifi -> "Off"
                    wifiIp != null -> "http://$wifiIp:$port"
                    else -> "Not on Wi-Fi"
                },
                tone = when {
                    !wifi -> Tone.Idle
                    wifiIp != null -> null
                    else -> Tone.Warn
                },
                mono = wifi && wifiIp != null
            )
            RowDivider()
            PortField(port = port, onPort = { value -> if (running) viewModel.updatePort(value) else ui.setStoppedPort(value) })
        }

        if (wifi) {
            TokenGroup(
                token = token,
                onRegenerate = { if (running) viewModel.regenerateToken() else ui.regenerateStoppedToken() }
            )
        }
    }
}

@Composable
private fun PortField(port: Int, onPort: (Int) -> Unit) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1024..65535
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Port", style = MaterialTheme.typography.bodyLarge, color = Ocb.Text)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter { it.isDigit() }.take(5)
                text.toIntOrNull()?.let { if (it in 1024..65535 && it != port) onPort(it) }
            },
            singleLine = true,
            isError = text.isNotEmpty() && !valid,
            textStyle = TelemetryStyle.copy(color = Ocb.Text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ocb.Accent,
                unfocusedBorderColor = Ocb.Border2,
                focusedContainerColor = Ocb.Bg,
                unfocusedContainerColor = Ocb.Bg
            )
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "1024–65535, default 8080. Must match the port set in OpenCamBridge on your computer.",
            style = MaterialTheme.typography.bodySmall,
            color = Ocb.Text3
        )
    }
}

@Composable
private fun TokenGroup(token: String, onRegenerate: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    SettingsGroup(
        title = "Access token",
        footer = "Enter this on your computer when connecting over Wi-Fi. A new token disconnects anyone using the old one."
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                when {
                    token.isBlank() -> "Created when you press Start"
                    visible -> token
                    else -> "•".repeat(24)
                },
                style = TelemetryStyle.copy(color = if (token.isBlank()) Ocb.Text3 else Ocb.Text),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = if (visible) "Hide" else "Show",
                    icon = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    onClick = { visible = !visible },
                    enabled = token.isNotBlank(),
                    height = 44.dp,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = "Copy",
                    icon = Icons.Filled.ContentCopy,
                    enabled = token.isNotBlank(),
                    height = 44.dp,
                    onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OpenCamBridge token", token))) } },
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = "New",
                    icon = Icons.Filled.Refresh,
                    height = 44.dp,
                    onClick = onRegenerate,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
