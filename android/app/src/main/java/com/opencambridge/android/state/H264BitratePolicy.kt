package com.opencambridge.android.state

/** One policy for initial configure and live encoder updates. */
internal object H264BitratePolicy {
    fun resolve(mode: String, requested: Int, width: Int, height: Int, fps: Int,
                minimum: Int, maximum: Int, accessMode: String = "usbOnly"): Int {
        require(mode == "auto" || mode == "manual")
        val pixels = width.toLong() * height
        val automatic = when {
            pixels >= 1920L * 1080 -> if (fps >= 50) 16_000_000 else 10_000_000
            pixels >= 1280L * 720 -> if (fps >= 50) 9_000_000 else 6_000_000
            else -> 3_000_000
        }
        // USB has a dedicated wired path. LAN leaves headroom for shared Wi-Fi
        // airtime and retransmissions instead of filling the AP's send queue.
        val transportTarget = if (accessMode == "usbOnly") automatic else automatic * 3 / 4
        return (if (mode == "auto") transportTarget else requested.coerceIn(1_000_000, 50_000_000))
            .coerceIn(minimum, maximum)
    }
}
