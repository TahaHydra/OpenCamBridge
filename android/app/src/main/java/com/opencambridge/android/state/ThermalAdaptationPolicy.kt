package com.opencambridge.android.state

/** Monotonic-clock policy; it never edits requested/persisted camera settings.
 * Android thermal levels are 0 none, 1 light, 2 moderate, 3 severe and 4+ critical.
 * Bitrate steps precede camera changes. Moderate is a hold band for recovery. */
internal class ThermalAdaptationPolicy {
    data class Decision(val level: Int, val bitrateScale: Double, val fpsCap: Int?, val cameraStep: Int)
    private var level = 0
    private var hotSince: Long? = null
    private var coolSince: Long? = null
    private var lastChange: Long? = null

    fun reset() {
        level = 0
        hotSince = null
        coolSince = null
        lastChange = null
    }

    fun current(): Decision = decision(level)

    fun evaluate(status: Int, nowMillis: Long, eligible: Boolean): Decision {
        if (!eligible) {
            reset()
            return current()
        }
        if (status >= 3) {
            coolSince = null
            val since = hotSince ?: nowMillis.also { hotSince = it }
            val dwell = if (status >= 4) 5_000L else 10_000L
            val interval = if (level < 2) 10_000L else 30_000L
            if (nowMillis - since >= dwell && (lastChange == null || nowMillis - lastChange!! >= interval)) {
                level = (level + 1).coerceAtMost(5)
                lastChange = nowMillis
            }
        } else {
            hotSince = null
            if (status <= 1 && level > 0) {
                val since = coolSince ?: nowMillis.also { coolSince = it }
                if (nowMillis - since >= 60_000L && (lastChange == null || nowMillis - lastChange!! >= 30_000L)) {
                    level--
                    lastChange = nowMillis
                }
            } else {
                coolSince = null
            }
        }
        return current()
    }

    private fun decision(level: Int) = Decision(
        level,
        when (level) { 0 -> 1.0; 1 -> 0.85; else -> 0.70 },
        if (level >= 3) 30 else null,
        (level - 3).coerceAtLeast(0)
    )
}

/** Low-latency Wi-Fi locks only have effect under these platform conditions. */
internal object WifiLatencyLockPolicy {
    fun eligible(api: Int, streaming: Boolean, foreground: Boolean, interactive: Boolean,
                 wifiConnected: Boolean, lanBound: Boolean, clients: Int, profile: String): Boolean =
        api >= 29 && streaming && foreground && interactive && wifiConnected && lanBound &&
            clients > 0 && profile != "smooth"
}
