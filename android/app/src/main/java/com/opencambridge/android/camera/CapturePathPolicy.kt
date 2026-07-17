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
                it != requested && (requested.fps > 30 || it.fps <= 30)
            }
        }
}
