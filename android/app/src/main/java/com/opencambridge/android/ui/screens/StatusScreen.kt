package com.opencambridge.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.UsbStatus
import com.opencambridge.android.ui.components.LogoMark
import com.opencambridge.android.ui.components.PrimaryButton
import com.opencambridge.android.ui.components.SecondaryButton
import com.opencambridge.android.ui.components.StatusDot
import com.opencambridge.android.ui.components.Tone
import com.opencambridge.android.ui.components.color

/** Where the phone is in its lifecycle, from the user's point of view. */
enum class PhonePhase { Stopped, Starting, Live, Paused, Failed, Stopping }

/** Enough to tell the user how a computer will reach this phone. */
data class ConnectionSummary(
    val accessMode: String,
    val port: Int,
    val wifiIp: String?,
    val usb: UsbStatus,
)

/**
 * Every state that is not "live": stopped, starting, paused by the computer,
 * failed and stopping. One calm, centred layout with a single main action.
 */
@Composable
fun StatusScreen(
    phase: PhonePhase,
    lastError: String,
    connection: ConnectionSummary,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val (label, tone, message) = when (phase) {
        PhonePhase.Starting -> Triple("STARTING", Tone.Busy, "Starting the camera and connection…")
        PhonePhase.Paused -> Triple(
            "PAUSED",
            Tone.Idle,
            "Your computer stopped the camera. The connection is still open, so the computer can start it again."
        )
        PhonePhase.Failed -> Triple("CAMERA ERROR", Tone.Danger, lastError.ifBlank { "The camera stopped unexpectedly." })
        PhonePhase.Stopping -> Triple("STOPPING", Tone.Busy, "Shutting down the camera and connection…")
        else -> Triple("STOPPED", Tone.Idle, "Camera and connection services are stopped.")
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Ocb.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val compact = maxHeight < 600.dp
        // At least a screen tall, so the footer sits at the bottom on tall
        // phones, and scrollable when the content is taller (landscape).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            StatusContent(phase, label, tone, message, compact, connection, onStart, onStop)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 24.dp)
            ) {
                FooterButton(Icons.Filled.Settings, "Settings", onOpenSettings)
                FooterButton(Icons.AutoMirrored.Filled.HelpOutline, "Help", onOpenHelp)
            }
        }
    }
}

@Composable
private fun StatusContent(
    phase: PhonePhase,
    label: String,
    tone: Tone,
    message: String,
    compact: Boolean,
    connection: ConnectionSummary,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(if (compact) 8.dp else 48.dp))
        LogoMark(size = if (compact) 48.dp else 64.dp)
        Spacer(Modifier.height(16.dp))
        Text("OpenCamBridge", style = MaterialTheme.typography.headlineSmall, color = Ocb.Text)
        Spacer(Modifier.height(if (compact) 20.dp else 36.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone, 12.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 3.sp, fontSize = if (compact) 24.sp else 30.sp),
                color = if (tone == Tone.Danger) Ocb.DangerText else Ocb.Text
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Ocb.Text2,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp)
        )
        Spacer(Modifier.height(if (compact) 24.dp else 40.dp))

        when (phase) {
            PhonePhase.Stopped, PhonePhase.Starting -> PrimaryButton(
                text = if (phase == PhonePhase.Starting) "Starting…" else "Start",
                onClick = onStart,
                loading = phase == PhonePhase.Starting,
                icon = Icons.Filled.PlayArrow,
                height = 60.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp)
            )
            PhonePhase.Paused, PhonePhase.Failed -> Column(
                modifier = Modifier.widthIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrimaryButton(
                    text = if (phase == PhonePhase.Failed) "Try again" else "Resume camera",
                    onClick = onStart,
                    icon = if (phase == PhonePhase.Failed) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                    height = 60.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                SecondaryButton(text = "Stop", onClick = onStop, danger = true, modifier = Modifier.fillMaxWidth())
            }
            else -> PrimaryButton(
                text = "Stopping…",
                onClick = {},
                loading = true,
                height = 60.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp)
            )
        }

        if (phase == PhonePhase.Stopped || phase == PhonePhase.Starting) {
            Spacer(Modifier.height(24.dp))
            ConnectionCard(connection)
        }
    }
}

@Composable
private fun ConnectionCard(connection: ConnectionSummary) {
    val wifi = connection.accessMode == "lanToken"
    val usb = connection.usb
    val (tone, title, detail) = if (wifi) {
        if (connection.wifiIp != null) {
            Triple(Tone.Ok, "Wi-Fi mode", "After Start, connect your PC to http://${connection.wifiIp}:${connection.port} with the access token from Settings.")
        } else {
            Triple(Tone.Warn, "Wi-Fi mode — not on Wi-Fi", "Connect this phone to the same Wi-Fi network as your PC.")
        }
    } else when {
        usb.connected && usb.debugging -> Triple(Tone.Ok, "USB connected", "Press Start, then connect from OpenCamBridge on your PC.")
        usb.connected -> Triple(Tone.Warn, "USB connected — debugging is off", "Turn on Developer options › USB debugging on this phone.")
        else -> Triple(Tone.Idle, "USB not connected", "Plug the phone into your PC, or choose Wi-Fi in Settings.")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(Ocb.RadiusCard))
            .background(Ocb.Surface)
            .border(1.dp, Ocb.Border, RoundedCornerShape(Ocb.RadiusCard))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(tone.color().copy(alpha = if (tone == Tone.Idle) 0.25f else 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (wifi) Icons.Filled.Wifi else Icons.Filled.Usb,
                contentDescription = null,
                tint = if (tone == Tone.Idle) Ocb.Text2 else tone.color(),
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ocb.Text)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Ocb.Text3)
        }
    }
}

@Composable
private fun FooterButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Ocb.Text2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ocb.Text2)
    }
}
