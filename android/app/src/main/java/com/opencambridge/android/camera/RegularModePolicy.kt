package com.opencambridge.android.camera

/** Platform-independent regular Camera2 mode construction. Keeping this pure
 * makes the important rule testable without a phone: a selectable FPS belongs
 * to an exact camera/output/size tuple and needs both AE-range and frame-duration
 * evidence. */
internal object RegularModePolicy {
    data class AeRange(val min: Int, val max: Int)
    data class Output(val width: Int, val height: Int, val minFrameDurationNs: Long)

    private val selectableRates = listOf(15, 24, 25, 30, 50, 60)

    /** Versioned NV12 ring: full-HD pixel area, either landscape or portrait.
     * Align chroma dimensions and reject ultrawide dimensions beyond its ABI. */
    fun fitsRing(width: Int, height: Int): Boolean = width > 0 && height > 0 &&
        width % 2 == 0 && height % 2 == 0 && width <= 1920 && height <= 1920 &&
        width.toLong() * height <= 1920L * 1080L

    fun build(
        cameraId: String,
        outputFormat: String,
        outputs: List<Output>,
        aeRanges: List<AeRange>,
    ): List<RegularCameraModeDto> = outputs.filter { fitsRing(it.width, it.height) }.flatMap { output ->
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

    fun selectRange(ranges: List<AeRange>, fps: Int, lowLightPriority: String = "stable"): AeRange? =
        ranges.asSequence()
            // Camera2 accepts only advertised ranges. A range containing 24
            // with upper=30 does not establish a 24 FPS output cadence.
            .filter { it.min <= fps && it.max == fps }
            .minWithOrNull(
                compareBy<AeRange>(
                    { if (lowLightPriority == "low-light") it.min else if (it.min == it.max) 0 else 1 },
                    { if (lowLightPriority == "low-light") it.max - fps else 0 },
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
