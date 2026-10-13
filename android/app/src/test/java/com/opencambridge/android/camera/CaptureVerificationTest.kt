package com.opencambridge.android.camera

import org.junit.Assert.*
import org.junit.Test

class CaptureVerificationTest {
    private fun evidence(captureFps: Double, encodeFps: Double, exposure: Long = 2_000_000, duration: Long = (1e9 / captureFps).toLong()): CaptureEvidence {
        fun times(fps: Double) = List((fps * 2).toInt() + 1) { 5_000_000_000L + (it * 1e9 / fps).toLong() }
        return CaptureEvidence(times(captureFps), times(encodeFps), List(60) { duration }, List(60) { exposure })
    }
    @Test fun real5994CaptureAndEncodedFramesVerify60() {
        assertEquals(CaptureVerdict.VERIFIED, CaptureVerification.evaluate(60, evidence(59.94, 59.94)).verdict)
    }
    @Test fun steady30InBrightConditionsRejectsTheRequestNotThePhone() {
        assertEquals(CaptureVerdict.REJECTED, CaptureVerification.evaluate(60, evidence(30.0, 30.0)).verdict)
    }
    @Test fun slowEncoderCannotPassOnSensorCadenceAlone() {
        assertEquals(CaptureVerdict.INCONCLUSIVE, CaptureVerification.evaluate(60, evidence(60.0, 30.0)).verdict)
    }
    @Test fun longExposureAndThermalLimitsRemainRetryable() {
        assertEquals(CaptureVerdict.INCONCLUSIVE, CaptureVerification.evaluate(60, evidence(30.0, 30.0, 30_000_000)).verdict)
        assertEquals(CaptureVerdict.INCONCLUSIVE, CaptureVerification.evaluate(60, evidence(30.0, 30.0), thermalLimited = true).verdict)
    }
    @Test fun missingDurationUsesTimestampEvidenceButDuplicatesDoNotProve60() {
        assertEquals(CaptureVerdict.VERIFIED, CaptureVerification.evaluate(60, evidence(60.0, 60.0, duration = 0)).verdict)
        assertEquals(CaptureVerdict.INCONCLUSIVE, CaptureVerification.evaluate(60, CaptureEvidence(List(120) { 7L }, List(120) { 7L }, emptyList(), emptyList())).verdict)
    }
    @Test fun noFramesAndShortMeasurementsAreInconclusive() {
        assertEquals(CaptureVerdict.INCONCLUSIVE, CaptureVerification.evaluate(60, CaptureEvidence(emptyList(), emptyList(), emptyList(), emptyList())).verdict)
        val short = evidence(60.0, 60.0).let { it.copy(captureNs = it.captureNs.take(10)) }
        assertEquals(CaptureVerdict.INCONCLUSIVE, CaptureVerification.evaluate(60, short).verdict)
    }
    @Test fun transientBackpressureCannotPassFromFastPtsAlone() {
        assertEquals(CaptureVerdict.INCONCLUSIVE, CaptureVerification.evaluate(60, evidence(60.0, 60.0), encodedDeliveryFps = 25.0).verdict)
    }
    @Test fun fallbackMustActuallyReduceCadenceAndCannotClaim30WhileStillDelivering60() {
        assertEquals(CaptureVerdict.INCONCLUSIVE, CaptureVerification.evaluate(30, evidence(60.0, 60.0)).verdict)
        assertEquals(CaptureVerdict.VERIFIED, CaptureVerification.evaluate(30, evidence(30.0, 30.0)).verdict)
    }
    @Test fun warmupIsExcludedAndMissingFramesExpireWithoutSupportClaims() {
        val samples = CaptureSampleWindow(0)
        for (index in 0..30) {
            val now = index * 33_333_333L
            samples.capture(10_000_000_000L + now, 33_333_333L, 2_000_000L, now)
            samples.encoded(10_000_000_000L + now, now)
        }
        assertFalse(samples.ready(1_000_000_000L))
        for (index in 0..125) {
            val now = 1_016_666_666L + index * 16_666_667L
            samples.capture(10_000_000_000L + now, 16_666_667L, 2_000_000L, now)
            samples.encoded(10_000_000_000L + now, now)
        }
        assertTrue(samples.ready(3_100_000_000L))
        assertEquals(CaptureVerdict.VERIFIED, samples.result().verdict)
        val empty = CaptureSampleWindow(0)
        assertTrue(empty.expired(6_000_000_000L))
        assertEquals(CaptureVerdict.INCONCLUSIVE, empty.result().verdict)
    }
    @Test fun retryWindowsAreBoundedAndVerifiedEvidenceIsNotOverwrittenByTemporaryFailure() {
        assertFalse(CaptureTrialPolicy.mayAttempt(CaptureVerdict.INCONCLUSIVE, 1_000, 30_000))
        assertTrue(CaptureTrialPolicy.mayAttempt(CaptureVerdict.INCONCLUSIVE, 1_000, 62_000))
        assertFalse(CaptureTrialPolicy.mayAttempt(CaptureVerdict.REJECTED, 1_000, 62_000))
        assertTrue(CaptureTrialPolicy.mayAttempt(CaptureVerdict.REJECTED, 1_000, 86_402_000))
        assertEquals(CaptureVerdict.VERIFIED, CaptureTrialPolicy.remember(CaptureVerdict.VERIFIED, CaptureVerdict.INCONCLUSIVE))
        assertTrue(CaptureTrialPolicy.mayAttempt(CaptureVerdict.VERIFIED, 1_000, 2_000))
    }
    @Test fun firmwareCodecAndActualPreviewConfigurationIdentifyDistinctEvidence() {
        val key = CaptureTrialKey("firmware-a", "0", 1920, 1080, 60, "codec-a", true, false)
        assertNotEquals(key, key.copy(fingerprint = "firmware-b"))
        assertNotEquals(key, key.copy(previewAttached = false))
        assertNotEquals(key, key.copy(codec = "codec-b"))
        assertNotEquals(key, key.copy(sessionParameters = true))
    }
}
