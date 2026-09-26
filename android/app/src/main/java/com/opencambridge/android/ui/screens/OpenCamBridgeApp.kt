package com.opencambridge.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencambridge.android.StreamViewModel
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.PhoneUiViewModel
import com.opencambridge.android.ui.ScreenCurtainHost
import com.opencambridge.android.ui.screens.settings.AboutPage
import com.opencambridge.android.ui.screens.settings.AdvancedSettingsPage
import com.opencambridge.android.ui.screens.settings.CameraSettingsPage
import com.opencambridge.android.ui.screens.settings.ConnectionSettingsPage
import com.opencambridge.android.ui.screens.settings.DiagnosticsPage
import com.opencambridge.android.ui.screens.settings.DisplaySettingsPage
import com.opencambridge.android.ui.screens.settings.LogsPage
import com.opencambridge.android.ui.screens.settings.SettingsHome
import com.opencambridge.android.ui.screens.settings.SettingsPage
import kotlinx.coroutines.delay

private sealed interface Route {
    data object Home : Route
    data class Settings(val page: SettingsPage) : Route
    data object Help : Route
}

private val BUSY_STATES = setOf("STREAMING", "RECONFIGURING", "RECOVERING")

/**
 * The phone app: a status screen with one big action while stopped, the live
 * camera while streaming, and everything else one tap away in Settings.
 *
 * Start and Stop are the Activity's own lifecycle operations, passed in
 * unchanged: Start launches (or resumes) the foreground service, Stop tears
 * down camera, stream, server and service. This UI only decides what to show.
 */
@Composable
fun OpenCamBridgeApp(
    viewModel: StreamViewModel,
    ui: PhoneUiViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetryStreamStart: () -> Unit,
) {
    val stack = remember { mutableStateListOf<Route>() }
    val route = stack.lastOrNull() ?: Route.Home
    val push: (Route) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    val serviceRunning by ui.serviceRunning.collectAsState()
    val lifecycle by viewModel.lifecycleState.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val clients by viewModel.clientCount.collectAsState()
    val curtain by ui.screenCurtain.collectAsState()
    val mirrorPreview by ui.mirrorPreview.collectAsState()
    val usb by ui.usb.collectAsState()
    val stopped by ui.stoppedSettings.collectAsState()
    val wifiIp by viewModel.wifiIp.collectAsState()
    val serviceStartError by viewModel.serviceStartError.collectAsState()
    val controlError by viewModel.controlError.collectAsState()

    // What the user just asked for, so the screen answers immediately instead
    // of flashing intermediate service states.
    var startRequested by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var dimRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(startRequested) { if (startRequested) { delay(15_000); startRequested = false } }
    LaunchedEffect(stopRequested) { if (stopRequested) { delay(10_000); stopRequested = false } }
    LaunchedEffect(serviceRunning, lifecycle, serviceStartError) {
        if (startRequested && serviceRunning && (lifecycle in BUSY_STATES || lifecycle == "FAILED")) startRequested = false
        if (serviceStartError != null) startRequested = false
        if (stopRequested && !serviceRunning) stopRequested = false
    }

    val phase = when {
        stopRequested -> PhonePhase.Stopping
        !serviceRunning -> if (startRequested) PhonePhase.Starting else PhonePhase.Stopped
        lifecycle in BUSY_STATES -> PhonePhase.Live
        lifecycle == "STARTING" -> PhonePhase.Starting
        lifecycle == "STOPPING" -> PhonePhase.Stopping
        lifecycle == "FAILED" -> if (startRequested) PhonePhase.Starting else PhonePhase.Failed
        else -> if (startRequested) PhonePhase.Starting else PhonePhase.Paused
    }

    val start = {
        startRequested = true
        onStart()
    }
    val stop = {
        stopRequested = true
        onStop()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(controlError) {
        controlError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearControlError()
        }
    }
    LaunchedEffect(serviceStartError) {
        serviceStartError?.let { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Retry",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite
            )
            viewModel.clearServiceStartError()
            if (result == SnackbarResult.ActionPerformed) {
                startRequested = true
                onRetryStreamStart()
            }
        }
    }

    Scaffold(
        containerColor = Ocb.Bg,
        // Screens place themselves around the system bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState, modifier = Modifier.navigationBarsPadding()) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Ocb.Surface3,
                    contentColor = Ocb.Text,
                    actionColor = Ocb.AccentText,
                    dismissActionContentColor = Ocb.Text3,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = route) {
                Route.Home -> ScreenCurtainHost(
                    streaming = phase == PhonePhase.Live,
                    clients = clients,
                    mode = curtain,
                    dimRequests = dimRequests
                ) {
                    if (phase == PhonePhase.Live) {
                        LiveScreen(
                            viewModel = viewModel,
                            mirrorPreview = mirrorPreview,
                            onStop = stop,
                            onOpenSettings = { push(Route.Settings(SettingsPage.Home)) },
                            onDim = { dimRequests++ }
                        )
                    } else {
                        StatusScreen(
                            phase = phase,
                            lastError = lastError,
                            connection = ConnectionSummary(stopped.accessMode, stopped.port, wifiIp, usb),
                            onStart = start,
                            onStop = stop,
                            onOpenSettings = { push(Route.Settings(SettingsPage.Home)) },
                            onOpenHelp = { push(Route.Help) }
                        )
                    }
                }
                Route.Help -> HelpScreen(onBack = pop)
                is Route.Settings -> when (current.page) {
                    SettingsPage.Home -> SettingsHome(
                        viewModel = viewModel,
                        ui = ui,
                        onNavigate = { push(Route.Settings(it)) },
                        onOpenHelp = { push(Route.Help) },
                        onBack = pop
                    )
                    SettingsPage.Camera -> CameraSettingsPage(viewModel, ui, onBack = pop)
                    SettingsPage.Display -> DisplaySettingsPage(viewModel, ui, onBack = pop)
                    SettingsPage.Connection -> ConnectionSettingsPage(viewModel, ui, onBack = pop)
                    SettingsPage.Advanced -> AdvancedSettingsPage(viewModel, ui, onNavigate = { push(Route.Settings(it)) }, onBack = pop)
                    SettingsPage.Diagnostics -> DiagnosticsPage(viewModel, onBack = pop)
                    SettingsPage.Logs -> LogsPage(viewModel, onBack = pop)
                    SettingsPage.About -> AboutPage(onBack = pop)
                }
            }
        }
    }
}
