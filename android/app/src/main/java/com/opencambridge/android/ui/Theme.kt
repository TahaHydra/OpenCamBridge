package com.opencambridge.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Camera body" — the phone's half of the OpenCamBridge design language.
 *
 * The desktop app is a rack instrument; this is the camera that feeds it, so it
 * shares the palette and the tally conventions (red = on air, green = ready,
 * amber = degraded) but is laid out viewfinder-first.
 *
 * The important constraint is the viewing distance. A phone propped up as a
 * webcam is read from across a desk, at an angle, in a glance that asks exactly
 * one question: is it live? So state is carried by large type and illuminated
 * colour rather than 12sp grey captions.
 */
object Ocb {
    // Surfaces: anodised graphite, warm-neutral rather than blue-black.
    val Void = Color(0xFF08090B)
    val Panel = Color(0xFF14171D)
    val PanelHigh = Color(0xFF1A1E26)
    val PanelTop = Color(0xFF21262F)
    val Inset = Color(0xFF090B0E)

    val Rule = Color(0x14FFFFFF)
    val Rule2 = Color(0x24FFFFFF)

    val Ink = Color(0xFFECEEF2)
    val Ink2 = Color(0xFF98A0AC)
    val Ink3 = Color(0xFF5D6673)
    val Ink4 = Color(0xFF3D444E)

    // Signal semantics, borrowed from tally conventions.
    val Tally = Color(0xFFFF2F2F)
    val TallyDim = Color(0xFF3A1210)
    val Ready = Color(0xFF2FD88A)
    val Warn = Color(0xFFFFB020)
    val Fail = Color(0xFFFF5C50)
    val Signal = Color(0xFF4FD8D3)
    val Key = Color(0xFFEEF1F5)

    val CornerPanel = 10.dp
    val CornerControl = 6.dp
}

/**
 * Silkscreen legends and large readouts use the device's condensed family
 * (Roboto Condensed on effectively every Android build). It carries the same
 * "equipment panel legend" character as the desktop's condensed display face
 * without bundling a font file or reaching out to a font CDN — which would
 * contradict this app's no-network promise.
 */
val CondensedFamily = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Bold)
)

/** Anything measured is monospaced, so digits do not jitter as values change. */
val MonoFamily = FontFamily.Monospace

/** Uppercase legend above a group of controls. */
val LegendStyle = TextStyle(
    fontFamily = CondensedFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 13.sp,
    letterSpacing = 1.8.sp
)

/** Small silkscreen label for a single field. */
val FieldLabelStyle = TextStyle(
    fontFamily = CondensedFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    letterSpacing = 1.2.sp
)

/** The big tally word. Sized to be read from across a desk. */
val TallyStyle = TextStyle(
    fontFamily = CondensedFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 30.sp,
    letterSpacing = 3.sp
)

/** A measured value. */
val ReadoutStyle = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp
)

val ReadoutLargeStyle = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp
)

val TelemetryStyle = TextStyle(
    fontFamily = MonoFamily,
    fontSize = 12.sp,
    lineHeight = 18.sp
)

@Composable
fun OpenCamBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ocb.Void,
            surface = Ocb.Panel,
            surfaceVariant = Ocb.PanelHigh,
            surfaceContainerHighest = Ocb.PanelTop,
            primary = Ocb.Signal,
            onPrimary = Ocb.Void,
            secondary = Ocb.Ink2,
            tertiary = Ocb.Ready,
            onTertiary = Ocb.Void,
            error = Ocb.Fail,
            onError = Ocb.Void,
            outline = Ocb.Rule2,
            outlineVariant = Ocb.Rule,
            onBackground = Ocb.Ink,
            onSurface = Ocb.Ink,
            onSurfaceVariant = Ocb.Ink2
        ),
        typography = Typography(
            // Body text stays in the platform sans; only legends, readouts and
            // the tally deliberately break away from it.
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = Ocb.Ink2),
            titleLarge = TextStyle(
                fontFamily = CondensedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 1.sp
            ),
            labelLarge = TextStyle(
                fontFamily = CondensedFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp
            ),
            labelMedium = FieldLabelStyle,
            labelSmall = TextStyle(
                fontFamily = CondensedFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        ),
        content = content
    )
}
