package com.opencambridge.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Surface

/** "1080p" for the usual webcam heights. */
internal fun describeHeight(height: Int): String = if (height > 0) "${height}p" else "—"

internal fun describeFormat(streamMode: String): String = if (streamMode == "mjpeg") "MJPEG" else "H.264"

/** "1080p · 30 fps · H.264" */
internal fun describeMode(height: Int, fps: Int, streamMode: String): String =
    "${describeHeight(height)} · ${if (fps > 0) fps else "—"} fps · ${describeFormat(streamMode)}"

/** Machine readout: dot decimal separator regardless of device locale. */
internal fun formatMbps(bitsPerSecond: Int, decimals: Int): String =
    String.format(java.util.Locale.US, "%.${decimals}f Mb/s", bitsPerSecond / 1_000_000f)

/** "OMX.qcom.video.encoder.avc" -> "qcom.avc", so it fits one line. */
internal fun shortCodec(name: String): String = name
    .removePrefix("OMX.")
    .removePrefix("c2.")
    .removePrefix("C2.")
    .replace("video.", "")
    .removeSuffix(".encoder")
    .removeSuffix("encoder.")
    .ifBlank { name }

internal fun engineLabel(engine: String): String = when (engine) {
    "REGULAR_SURFACE" -> "Camera2 surface (direct)"
    "HIGH_SPEED_GPU_BRIDGE" -> "High-speed GPU bridge"
    "" -> "—"
    else -> engine.lowercase().replace('_', ' ')
}

/** Surface.ROTATION_* to degrees, matching FrameTransformPolicy. */
internal fun surfaceRotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
