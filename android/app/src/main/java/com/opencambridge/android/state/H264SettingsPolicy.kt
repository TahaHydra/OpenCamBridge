package com.opencambridge.android.state

/**
 * The webcam transport promises a keyframe at least once per second so a
 * reconnect has bounded recovery time. Older builds allowed larger persisted
 * values; normalize them instead of letting that stale preference reject every
 * otherwise unrelated settings patch.
 */
internal object H264SettingsPolicy {
    const val KEYFRAME_INTERVAL_SECONDS = 1

    fun normalizeKeyframeInterval(value: Int): Int =
        value.coerceIn(KEYFRAME_INTERVAL_SECONDS, KEYFRAME_INTERVAL_SECONDS)
}
