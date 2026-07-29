package com.opencambridge.android.camera

/** Platform-independent regular Camera2 mode construction. Keeping this pure
 * makes the important rule testable without a phone: a selectable FPS belongs
 * to an exact camera/output/size tuple and needs both AE-range and frame-duration
 * evidence. */
internal object RegularModePolicy {
    data class AeRange(val min: Int, val max: Int)
    data class Output(val width: Int, val height: Int, val minFrameDurationNs: Long)

    private val selectableRates = listOf(15, 30, 60)

    fun build(
        cameraId: String,
        outputFormat: String,
        outputs: List<Output>,
        aeRanges: List<AeRange>,
    ): List<RegularCameraModeDto> = outputs.flatMap { output ->
        selectableRates.mapNotNull { fps ->
            val range = selectRange(aeRanges, fps) ?: return@mapNotNull null
            if (!durationSupports(output.minFrameDurationNs, fps)) return@mapNotNull null
            RegularCameraModeDto(
                cameraId = cameraId,
                width = output.width,
                height = output.height,
                outputFormat = outputFormat,
                fps = fps,
                aeFpsMin = range.min,
                aeFpsMax = range.max,
                minFrameDurationNs = output.minFrameDurationNs,
            )
        }
    }.distinctBy { Triple(it.width, it.height, it.fps) }
        .sortedWith(
            compareByDescending<RegularCameraModeDto> { it.width.toLong() * it.height }
                .thenByDescending { it.fps }
        )

    fun selectRange(ranges: List<AeRange>, fps: Int): AeRange? =
        ranges.asSequence()
            .filter { fps in it.min..it.max }
            .minWithOrNull(
                compareBy<AeRange>(
                    { if (it.min == it.max) 0 else 1 },
                    { it.max - it.min },
                    { -it.min },
                )
            )

    /** Camera vendors commonly round 59.94 to a nominal 60 FPS. Permit a small
     * 0.5% duration tolerance, but never infer a mode from an unknown duration. */
    fun durationSupports(minFrameDurationNs: Long, fps: Int): Boolean {
        if (minFrameDurationNs <= 0L || fps <= 0) return false
        return minFrameDurationNs.toDouble() * fps.toDouble() <= 1_005_000_000.0
    }
}
