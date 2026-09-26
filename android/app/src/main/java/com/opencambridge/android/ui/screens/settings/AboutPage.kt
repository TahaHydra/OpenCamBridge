package com.opencambridge.android.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.components.LogoMark
import com.opencambridge.android.ui.components.NavRow
import com.opencambridge.android.ui.components.RowDivider
import com.opencambridge.android.ui.components.SettingsGroup

private const val REPOSITORY = "https://github.com/TahaHydra/OpenCamBridge"

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    SettingsScaffold(title = "About", onBack = onBack) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(size = 64.dp)
            Spacer(Modifier.height(12.dp))
            Text("OpenCamBridge", style = MaterialTheme.typography.headlineSmall, color = Ocb.Text)
            if (version.isNotBlank()) Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = Ocb.Text3)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your phone as a Windows webcam. Free and open source — no cloud, no account, no telemetry.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ocb.Text2,
                textAlign = TextAlign.Center
            )
        }
        SettingsGroup(
            title = "Project",
            footer = "OpenCamBridge is licensed under the GNU General Public License v3.0 or later. " +
                "The Windows virtual camera is derived from Microsoft's VirtualCamera sample (MIT)."
        ) {
            NavRow("GitHub repository", onClick = { openLink(context, REPOSITORY) }, icon = Icons.AutoMirrored.Filled.OpenInNew)
            RowDivider()
            NavRow("Report a problem", onClick = { openLink(context, "$REPOSITORY/issues") }, icon = Icons.Filled.BugReport)
            RowDivider()
            NavRow("Licences", onClick = { openLink(context, "$REPOSITORY/blob/main/LICENSES.md") }, icon = Icons.Filled.Gavel)
        }
        Text(
            "Copyright © 2026 TahaHydra and contributors.",
            style = MaterialTheme.typography.bodySmall,
            color = Ocb.Text4,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // No browser installed: nothing sensible to do.
    }
}
