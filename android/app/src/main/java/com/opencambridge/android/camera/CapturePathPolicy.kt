package com.opencambridge.android.camera

/** Pure policy kept separate from Android framework queries so path ordering
 * and high-speed pacing decisions are deterministic unit-test targets. */
internal object CapturePathPolicy {
    fun directRange(ranges: List<Pair<Int, Int>>, outputFps: Int): Pair<Int, Int>? =
        ranges.filter { (lower, upper) -> upper == outputFps && lower <= outputFps }
            .minWithOrNull(compareBy<Pair<Int, Int>>({ it.second - it.first }, { -it.first }))

    fun bridgeRange(ranges: List<Pair<Int, Int>>, outputFps: Int): Pair<Int, Int>? =
        ranges.filter { (_, upper) -> upper >= outputFps && upper % outputFps == 0 }
            .minWithOrNull(compareBy<Pair<Int, Int>>({ it.second }, { it.second - it.first }))

    fun adaptiveModes(requested: H264ModeDto, preferred: List<H264ModeDto>): List<H264ModeDto> =
        buildList {
            add(requested)
            preferred.filterTo(this) {
                it != requested &&
                    it.width <= requested.width &&
                    it.height <= requested.height &&
                    it.fps <= requested.fps
            }
        }.distinct()

    /** Resume an adaptive ladder at [firstMode]. This remains based on the
     * original desired tuple: 1080p60 -> 720p60 may continue to 1080p30 when
     * the intermediate high-speed session is rejected at runtime. */
    fun adaptiveSuffix(
        requested: H264ModeDto,
        firstMode: H264ModeDto,
        preferred: List<H264ModeDto>
    ): List<H264ModeDto> {
        val modes = adaptiveModes(requested, preferred)
        val firstIndex = modes.indexOf(firstMode)
        return if (firstIndex >= 0) modes.drop(firstIndex) else emptyList()
    }

    fun candidateModes(
        profile: String,
        requested: H264ModeDto,
        preferred: List<H264ModeDto>
    ): List<H264ModeDto> = if (profile == "adaptive") adaptiveModes(requested, preferred) else listOf(requested)

    /** Select a real compatibility tuple without mutating the desired H.264
     * request. Resolution and frame rate may only move down. The exact source
     * resolution is preferred before a lower-resolution alternative. */
    fun selectMjpegFallback(requested: H264ModeDto, supported: List<H264ModeDto>): H264ModeDto? =
        supported
            .asSequence()
            .filter {
                it.width <= requested.width && it.height <= requested.height && it.fps <= requested.fps
            }
            .sortedWith(
                compareByDescending<H264ModeDto> { it.width == requested.width && it.height == requested.height }
                    .thenByDescending { it.width.toLong() * it.height }
                    .thenByDescending { it.fps }
            )
            .firstOrNull()

    /** MJPEG remains compatibility-only and uses the regular CameraX analysis
     * path. Never expose 60 unless that exact camera/size path reports it. */
    fun selectableMjpegFps(maxRegularFps: Int): List<Int> =
        listOf(15, 30, 60).filter { it <= maxRegularFps }
}
