package com.opencambridge.android.camera

internal enum class CaptureVerdict { VERIFIED, REJECTED, INCONCLUSIVE }
internal data class CaptureEvidence(val captureNs: List<Long>, val encodedNs: List<Long>, val durationNs: List<Long>, val exposureNs: List<Long>)
internal data class CaptureVerificationResult(val verdict: CaptureVerdict, val captureFps: Double, val encodedFps: Double, val reason: String)
internal data class CaptureTrialKey(val fingerprint: String, val cameraId: String, val width: Int, val height: Int, val fps: Int, val codec: String, val previewAttached: Boolean, val sessionParameters: Boolean)

internal object CaptureVerification {
    private fun rate(times: List<Long>, minimumSpanNs: Long): Double {
        if (times.size < 10 || times.zipWithNext().any { (a, b) -> a <= 0 || b <= a }) return 0.0
        val elapsed = times.last() - times.first()
        return if (elapsed >= minimumSpanNs) (times.size - 1) * 1e9 / elapsed else 0.0
    }
    private fun median(values: List<Long>): Long = values.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    fun evaluate(target: Int, evidence: CaptureEvidence, thermalLimited: Boolean = false, encodedDeliveryFps: Double = Double.MAX_VALUE, minimumSpanNs: Long = 1_800_000_000L): CaptureVerificationResult {
        val capture = rate(evidence.captureNs, minimumSpanNs)
        val encoded = rate(evidence.encodedNs, minimumSpanNs)
        fun result(verdict: CaptureVerdict, reason: String) = CaptureVerificationResult(verdict, capture, encoded, reason)
        if (target <= 0 || capture == 0.0 || encoded == 0.0) return result(CaptureVerdict.INCONCLUSIVE, "Insufficient steady capture/encoder timestamps; retry is available")
        val targetDuration = 1e9 / target
        val duration = median(evidence.durationNs).takeIf { it > 0 }
            ?: median(evidence.captureNs.zipWithNext { a, b -> b - a })
        if (capture in target * 0.9..target * 1.1 && encoded in target * 0.9..target * 1.1 && encodedDeliveryFps >= target * 0.9 && duration <= targetDuration * 1.05)
            return result(CaptureVerdict.VERIFIED, "Measured regular-session $target FPS capture and encoding")
        val lowLight = median(evidence.exposureNs) > targetDuration * 1.05
        if (!thermalLimited && !lowLight && capture in 25.0..33.0 && encoded in 25.0..33.0 && duration >= 28_000_000L)
            return result(CaptureVerdict.REJECTED, "Fixed $target request stayed near 30 FPS; this request configuration can be retried")
        return result(CaptureVerdict.INCONCLUSIVE, when {
            thermalLimited -> "Temporarily limited by thermal status; retry after cooling"
            lowLight -> "Long exposure limits cadence; retry in brighter light"
            else -> "Capture/encoder delivery did not sustain $target FPS; retry is available"
        })
    }
}
internal object CaptureTrialPolicy {
    fun mayAttempt(previous: CaptureVerdict?, measuredAt: Long, now: Long): Boolean =
        previous == null || previous == CaptureVerdict.VERIFIED || now < measuredAt || now - measuredAt >= when (previous) {
            CaptureVerdict.REJECTED -> 86_400_000L
            else -> 60_000L
        }
    fun remember(previous: CaptureVerdict?, next: CaptureVerdict): CaptureVerdict =
        if (previous == CaptureVerdict.VERIFIED) previous else next
}

/** Short-lived, bounded collector; never retains frame payloads. */
internal class CaptureSampleWindow(private val startedNs: Long, private val targetFps: Int = 60,
    private val warmupNs: Long = 1_000_000_000L, private val windowNs: Long = 2_000_000_000L) {
    private var firstCaptureAt = -1L
    private var windowAt = -1L
    private val captures = ArrayList<Long>(180)
    private val encoded = ArrayList<Long>(180)
    private val durations = ArrayList<Long>(180)
    private val exposures = ArrayList<Long>(180)
    private val deliveries = ArrayList<Long>(180)
    private var captureCount = 0
    private var encodedCount = 0
    @Synchronized fun capture(timestamp: Long, duration: Long, exposure: Long, now: Long) {
        if (timestamp <= 0) return
        captureCount++
        if (firstCaptureAt < 0) firstCaptureAt = now
        if (now - firstCaptureAt < warmupNs) return
        if (windowAt < 0) windowAt = now
        if (captures.size < 256 && (captures.isEmpty() || timestamp > captures.last())) {
            captures.add(timestamp); durations.add(duration); exposures.add(exposure)
        }
    }
    @Synchronized fun encoded(timestamp: Long, now: Long) {
        encodedCount++
        if (windowAt < 0 || timestamp <= 0) return
        if (encoded.size < 256 && (encoded.isEmpty() || timestamp > encoded.last())) {
            encoded.add(timestamp); deliveries.add(now)
        }
    }
    @Synchronized fun ready(now: Long): Boolean = windowAt >= 0 && now - windowAt >= windowNs &&
        captures.size > 1 && encoded.size > 1 && captures.last() - captures.first() >= windowNs * 9 / 10 && encoded.last() - encoded.first() >= windowNs * 9 / 10
    fun expired(now: Long): Boolean = now - startedNs >= 6_000_000_000L
    @Synchronized fun alive(): Boolean = captureCount > 2 && encodedCount > 2
    @Synchronized fun result(thermalLimited: Boolean = false): CaptureVerificationResult =
        CaptureVerification.evaluate(targetFps, CaptureEvidence(captures.toList(), encoded.toList(), durations.toList(), exposures.toList()), thermalLimited,
            if (deliveries.size > 1 && deliveries.last() > deliveries.first()) (deliveries.size - 1) * 1e9 / (deliveries.last() - deliveries.first()) else 0.0, windowNs * 9 / 10)
}
