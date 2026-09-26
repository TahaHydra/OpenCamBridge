package com.opencambridge.android.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.opencambridge.android.StreamViewModel
import com.opencambridge.android.ui.PhoneUiViewModel
import com.opencambridge.android.ui.ScreenCurtainMode
import com.opencambridge.android.ui.components.NavRow
import com.opencambridge.android.ui.components.RowDivider
import com.opencambridge.android.ui.components.SegmentedRow
import com.opencambridge.android.ui.components.SettingsGroup
import com.opencambridge.android.ui.components.SwitchRow
import com.opencambridge.android.ui.describe

@Composable
fun DisplaySettingsPage(viewModel: StreamViewModel, ui: PhoneUiViewModel, onBack: () -> Unit) {
    val running by ui.serviceRunning.collectAsState()
    val stopped by ui.stoppedSettings.collectAsState()
    val curtain by ui.screenCurtain.collectAsState()
    val mirrorPreview by ui.mirrorPreview.collectAsState()
    val livePreview by viewModel.localPreviewEnabled.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()
    val context = LocalContext.current
    val batteryUnrestricted = remember {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }

    SettingsScaffold(title = "Display & power", onBack = onBack) {
        SettingsGroup(title = "Screen curtain", footer = curtain.describe()) {
            SegmentedRow(
                title = "Dim the screen while streaming",
                subtitle = "Saves battery, heat and screen wear. The camera keeps running.",
                options = ScreenCurtainMode.entries.map { it to it.label },
                selected = curtain,
                onSelect = ui::setScreenCurtain
            )
        }

        SettingsGroup(title = "Preview on this phone") {
            SwitchRow(
                title = "Show preview",
                subtitle = if (running) {
                    "Off saves battery. Your computer still receives video. Changing it briefly restarts the camera."
                } else {
                    "Off saves battery. Your computer still receives video."
                },
                checked = if (running) livePreview else stopped.localPreviewEnabled,
                enabled = !running || !rebinding,
                onChange = { enabled -> if (running) viewModel.toggleLocalPreview(enabled) else ui.setStoppedPhonePreview(enabled) }
            )
            RowDivider()
            SwitchRow(
                title = "Mirror preview",
                subtitle = "Flips only what you see on this phone, like a mirror. Your computer's video is unchanged.",
                checked = mirrorPreview,
                onChange = ui::setMirrorPreview
            )
        }

        SettingsGroup(
            title = "Battery",
            footer = if (batteryUnrestricted) null else "Android may pause the camera when the screen locks. Choose Unrestricted for OpenCamBridge."
        ) {
            NavRow(
                title = "Battery optimisation",
                subtitle = if (batteryUnrestricted) "Unrestricted — best for long sessions" else "Optimised by Android",
                trailingText = if (batteryUnrestricted) "Unrestricted" else "Change",
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            )
        }
    }
}
