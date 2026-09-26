package com.opencambridge.android.ui

import android.os.SystemClock
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
    var curtained by remember { mutableStateOf(false) }

    // Inactivity: every touch restarts the countdown.
    LaunchedEffect(eligible, lastTouch, curtained) {
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
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(activity, curtained) {
        val window = activity?.window
        if (curtained && window != null) {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (curtained && window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
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

