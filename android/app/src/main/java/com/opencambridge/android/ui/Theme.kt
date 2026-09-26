package com.opencambridge.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The phone half of the OpenCamBridge design system, matching the desktop app:
 * dark charcoal surfaces, one accent colour, and status colours that only ever
 * mean status — green healthy, yellow warning, red error or destructive.
 *
 * The phone is mostly a capture appliance propped up across a desk, so the
 * interface is sparse and legible at a glance: one state, one big action.
 * No bundled fonts and no font CDN — the app never touches the network for UI.
 */
object Ocb {
    // Surfaces, darkest to lightest.
    val Bg = Color(0xFF0F1114)
    val Surface = Color(0xFF181B21)
    val Surface2 = Color(0xFF1F232A)
    val Surface3 = Color(0xFF282D36)
    val Surface4 = Color(0xFF313743)

    val Border = Color(0x12FFFFFF)
    val Border2 = Color(0x1CFFFFFF)

    // Text.
    val Text = Color(0xFFECEEF2)
    val Text2 = Color(0xFFA8AFB9)
    val Text3 = Color(0xFF737B86)
    val Text4 = Color(0xFF4D545E)

    // The one accent.
    val Accent = Color(0xFF4D8DFF)
    val AccentText = Color(0xFF9CC0FF)
    val AccentSoft = Color(0x244D8DFF)

    // Status — never decoration.
    val Ok = Color(0xFF3FCF7F)
    val OkSoft = Color(0x213FCF7F)
    val Warn = Color(0xFFF3B33D)
    val WarnSoft = Color(0x21F3B33D)
    val Danger = Color(0xFFF2574C)
    val DangerText = Color(0xFFFFAAA3)
    val DangerSoft = Color(0x21F2574C)

    // Spacing scale (8/12/16/24) and shapes.
    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 24.dp
    val S6 = 32.dp

    val RadiusControl = 12.dp
    val RadiusCard = 16.dp
    val ControlHeight = 48.dp
}

/** Measured values use tabular figures so digits do not jitter as they change. */
val MonoFamily = FontFamily.Monospace

val TelemetryStyle = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 18.sp)

/** Small uppercase group heading. */
val SectionLabelStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp,
    letterSpacing = 0.8.sp
)

@Composable
fun OpenCamBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ocb.Bg,
            surface = Ocb.Surface,
            surfaceVariant = Ocb.Surface2,
            surfaceContainer = Ocb.Surface,
            surfaceContainerHigh = Ocb.Surface2,
            surfaceContainerHighest = Ocb.Surface3,
            primary = Ocb.Accent,
            onPrimary = Color.White,
            primaryContainer = Ocb.AccentSoft,
            onPrimaryContainer = Ocb.AccentText,
            secondary = Ocb.Text2,
            tertiary = Ocb.Ok,
            error = Ocb.Danger,
            onError = Color.White,
            outline = Ocb.Border2,
            outlineVariant = Ocb.Border,
            onBackground = Ocb.Text,
            onSurface = Ocb.Text,
            onSurfaceVariant = Ocb.Text2
        ),
        typography = Typography(
            displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, color = Ocb.Text3),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.2.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.5.sp, letterSpacing = 0.3.sp)
        ),
        content = content
    )
}
