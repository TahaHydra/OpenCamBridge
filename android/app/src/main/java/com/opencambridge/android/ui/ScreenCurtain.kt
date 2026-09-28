package com.opencambridge.android.ui

import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

private const val ALWAYS_DELAY_MS = 5_000L
private const val AUTO_DELAY_MS = 15_000L

/**
 * Screen curtain: while the camera streams, black the phone out to save power,
 * heat and OLED wear. Capture is unaffected — only this window goes dark.
 *
 * - Auto: after 15 s without a touch, while a computer is receiving video.
 * - Always: after 5 s without a touch, whenever the camera is streaming.
 * - Never: only when asked for with the Dim button.
 *
 * While a curtain can engage, the screen is kept on: locking the phone makes
 * some Android builds revoke camera access, which is exactly what a phone
 * propped up as a webcam must avoid. A touch lifts the curtain instantly.
 */
@Composable
fun ScreenCurtainHost(
    streaming: Boolean,
    clients: Int,
    mode: ScreenCurtainMode,
    dimRequests: Int,
    content: @Composable () -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    val eligible = streaming && when (mode) {
        ScreenCurtainMode.Never -> false
        ScreenCurtainMode.Always -> true
        ScreenCurtainMode.Auto -> clients > 0
    }
    val idleDelay = if (mode == ScreenCurtainMode.Always) ALWAYS_DELAY_MS else AUTO_DELAY_MS
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var curtained by remember(streaming, mode) { mutableStateOf(false) }

    // Inactivity: every touch restarts the countdown.
    LaunchedEffect(eligible, idleDelay, lastTouch, curtained) {
        if (eligible && !curtained) {
            delay(idleDelay)
            curtained = true
        }
    }
    // The Dim button curtains immediately, in any mode.
    LaunchedEffect(dimRequests) {
        if (dimRequests > 0 && streaming) curtained = true
    }
    LaunchedEffect(streaming) {
        if (!streaming) curtained = false
    }

    val keepOn = streaming && (eligible || curtained)
    DisposableEffect(activity, keepOn) {
        val window = activity?.window
        val alreadyKeptOn = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (keepOn && !alreadyKeptOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(activity, curtained) {
        val window = activity?.window
        val lease = if (curtained && window != null) CurtainWindowLease(AndroidCurtainWindow(window)) else null
        onDispose { lease?.restore() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Observe every touch without consuming it, so the controls
                // underneath behave normally.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.uptimeMillis()
                    }
                }
            }
    ) {
        content()
        if (curtained) {
            Curtain(onWake = {
                curtained = false
                lastTouch = SystemClock.uptimeMillis()
            })
        }
    }
}

private class AndroidCurtainWindow(private val window: Window) : CurtainWindow {
    private val bars = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    private val controller = WindowCompat.getInsetsController(window, window.decorView)
    override var state: CurtainWindowState
        get() {
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
            val visible = insets?.let {
                (if (it.isVisible(WindowInsetsCompat.Type.statusBars())) WindowInsetsCompat.Type.statusBars() else 0) or
                    (if (it.isVisible(WindowInsetsCompat.Type.navigationBars())) WindowInsetsCompat.Type.navigationBars() else 0)
            }
            return CurtainWindowState(window.attributes.screenBrightness, visible, controller.systemBarsBehavior)
        }
        set(value) {
            window.attributes = window.attributes.apply { screenBrightness = value.brightness }
            value.visibleBars?.let { visible ->
                controller.systemBarsBehavior = value.barsBehavior
                val hidden = bars and visible.inv()
                if (hidden != 0) controller.hide(hidden)
                if (visible != 0) controller.show(visible)
            }
        }

    override fun dim() {
        val previous = state
        state = previous.copy(
            brightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF,
            // If insets are unavailable, don't mutate visibility we cannot restore.
            visibleBars = previous.visibleBars?.let { 0 },
            barsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
        )
    }
}

@Composable
private fun Curtain(onWake: () -> Unit) {
    // The only lit pixels drift between positions so nothing burns in.
    var slot by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(45_000)
            slot = (slot + 1) % 4
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onWake)
    ) {
        val x = if (slot % 2 == 0) maxWidth * 0.18f else maxWidth * 0.46f
        val y = if (slot < 2) maxHeight * 0.3f else maxHeight * 0.62f
        Text(
            "Streaming · tap to wake",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF3A3F47),
            modifier = Modifier.offset(x = x, y = y)
        )
    }
}

/** Mode descriptions for Settings. */
fun ScreenCurtainMode.describe(): String = when (this) {
    ScreenCurtainMode.Auto -> "Dims to black after 15 seconds while your computer is receiving video. Tap to wake."
    ScreenCurtainMode.Always -> "Dims to black 5 seconds after your last touch whenever the camera is on."
    ScreenCurtainMode.Never -> "The screen follows your normal timeout. Use Dim on the camera screen to black it out."
}
