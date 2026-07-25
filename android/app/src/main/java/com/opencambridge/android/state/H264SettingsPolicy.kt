package com.opencambridge.android.state

/**
 * Keyframe cadence for the OCB2 transport.
 *
 * This used to be pinned to exactly one second on the grounds that a reconnect
 * needs bounded recovery time. Recovery never actually depended on it: an IDR is
 * requested explicitly whenever a client subscribes, whenever orientation
 * metadata changes, and after any discontinuity — and a producer-side decoder
 * reset ends in a reconnect, which subscribes again. What the one-second GOP did
 * cost was picture quality: at 60 fps under CBR the rate controller had to fit an
 * IDR (plus in-band SPS/PPS) into the budget every second, which shows up as
 * periodic softening.
 *
 * So the interval is now a real range with a multi-second default. The upper
 * bound keeps a safety net for a consumer that somehow misses every explicit
 * request; the lower bound still allows 1 s for anyone who wants the old
 * behaviour. Older persisted values outside the range are migrated rather than
 * allowed to reject an otherwise unrelated settings patch.
 */
internal object H264SettingsPolicy {
    /** Chosen so an IDR costs ~1/5 as much of the bitrate budget as at 1 s. */
    const val DEFAULT_KEYFRAME_INTERVAL_SECONDS = 5
    const val MIN_KEYFRAME_INTERVAL_SECONDS = 1
    const val MAX_KEYFRAME_INTERVAL_SECONDS = 10

    fun normalizeKeyframeInterval(value: Int): Int =
        if (value < MIN_KEYFRAME_INTERVAL_SECONDS) {
            // Zero and negatives are not "as fast as possible", they are absent
            // or corrupt; fall back to the default rather than the minimum.
            DEFAULT_KEYFRAME_INTERVAL_SECONDS
        } else {
            value.coerceAtMost(MAX_KEYFRAME_INTERVAL_SECONDS)
        }
}
