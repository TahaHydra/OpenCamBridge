package com.opencambridge.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.components.RowDivider
import com.opencambridge.android.ui.components.SettingsGroup
import com.opencambridge.android.ui.screens.settings.SettingsScaffold

@Composable
fun HelpScreen(onBack: () -> Unit) {
    SettingsScaffold(title = "Help", onBack = onBack) {
        SettingsGroup(title = "Connect with USB (recommended)", footer = "Video stays inside the cable and never touches your network.") {
            Step(1, "Install OpenCamBridge on your Windows PC.")
            RowDivider()
            Step(2, "On this phone, turn on Developer options › USB debugging.")
            RowDivider()
            Step(3, "Plug the phone into the PC and accept “Allow USB debugging”.")
            RowDivider()
            Step(4, "Tap Start here, then Connect in OpenCamBridge on the PC.")
        }
        SettingsGroup(title = "Connect over Wi-Fi") {
            Step(1, "In Settings › Connection, choose Wi-Fi + token.")
            RowDivider()
            Step(2, "Tap Start. Keep both devices on the same network.")
            RowDivider()
            Step(3, "On the PC choose Wi-Fi, then enter the address and access token shown in Settings › Connection.")
        }
        SettingsGroup(title = "Good to know") {
            Tip("Stop means stop", "Stop turns off the camera, the connection and the background service. Your computer cannot restart it — start it again from this phone.")
            RowDivider()
            Tip("Screen curtain", "While streaming, the screen can dim to black to save battery and heat. The camera keeps running; tap to wake.")
            RowDivider()
            Tip("Long sessions", "If the camera stops when the phone locks, set OpenCamBridge's battery usage to Unrestricted in Settings › Display & power.")
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(26.dp).background(Ocb.AccentSoft, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = Ocb.AccentText)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ocb.Text, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Tip(title: String, text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Ocb.Text)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ocb.Text3)
    }
}
