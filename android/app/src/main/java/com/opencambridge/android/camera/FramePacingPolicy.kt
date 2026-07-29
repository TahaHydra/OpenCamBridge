package com.opencambridge.android.camera

/** Decide from the camera capture timeline, never analyzer callback arrival
 * time. CameraX may deliver a frame queued behind JPEG work immediately after
 * the previous ImageProxy closes; callback time would misclassify it as an
 * over-rate frame even though the captures are a full source interval apart. */
internal object FramePacingPolicy {
    fun shouldEncode(
        captureTimestampNs: Long,
        lastEncodedCaptureTimestampNs: Long,
        targetFps: Int,
        idle: Boolean,
    ): Boolean {
        if (lastEncodedCaptureTimestampNs <= 0L ||
            captureTimestampNs <= lastEncodedCaptureTimestampNs) return true
        val target = targetFps.coerceIn(1, 120)
        val minimumIntervalNs = (1_000_000_000L / target) * 9 / 10
        val requiredIntervalNs = if (idle) 500_000_000L else minimumIntervalNs
        return captureTimestampNs - lastEncodedCaptureTimestampNs >= requiredIntervalNs
    }
}
