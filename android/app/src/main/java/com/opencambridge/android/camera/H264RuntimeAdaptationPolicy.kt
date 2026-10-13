package com.opencambridge.android.camera

/** Desired settings stay immutable; only fully automatic quality can adapt. */
internal object H264RuntimeAdaptationPolicy {
    data class Effective(val bitrateScale: Double, val fpsCap: Int?)
    fun streamFps(selectedFps: Int, effectiveCap: Int?): Int =
        minOf(selectedFps.coerceAtLeast(1), effectiveCap ?: selectedFps.coerceAtLeast(1))
    fun resolve(profile: String, bitrateMode: String, scale: Double, fpsCap: Int?, requestedFps: Int): Effective {
        if (profile != "adaptive" || bitrateMode != "auto") return Effective(1.0, null)
        return Effective(if (scale.isFinite()) scale.coerceIn(0.25, 1.0) else 1.0,
            fpsCap?.coerceIn(1, requestedFps.coerceAtLeast(1)))
    }

    /** Once FPS is capped, every thermal camera step must reduce pixel work.
     * The normal performance ladder prioritizes cadence and can raise pixel
     * area (720p60 -> 1080p30), which is unsuitable under an existing 30 cap. */
    fun thermalMode(current: H264ModeDto, supported: List<H264ModeDto>, fpsCap: Int): H264ModeDto? =
        supported.filter {
            it.width.toLong() * it.height < current.width.toLong() * current.height &&
                it.fps <= minOf(current.fps, fpsCap)
        }.maxWithOrNull(compareBy<H264ModeDto> { it.width.toLong() * it.height }.thenBy { it.fps })
}
