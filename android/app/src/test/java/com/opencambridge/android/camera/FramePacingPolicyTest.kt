package com.opencambridge.android.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingPolicyTest {
    @Test
    fun queuedDeliveryStillAcceptsCameraFramesOneSourceIntervalApart() {
        val previousCapture = 1_000_000_000L
        // Analyzer callbacks may be close together, but these camera timestamps
        // are a real 30 FPS interval apart and must both be encoded.
        assertTrue(FramePacingPolicy.shouldEncode(
            previousCapture + 33_333_333L, previousCapture, 30, idle = false
        ))
    }

    @Test
    fun genuineOverRateFrameIsSkipped() {
        assertFalse(FramePacingPolicy.shouldEncode(
            1_010_000_000L, 1_000_000_000L, 30, idle = false
        ))
    }

    @Test
    fun idleModeRetainsOnlyARecentFrame() {
        assertFalse(FramePacingPolicy.shouldEncode(
            1_400_000_000L, 1_000_000_000L, 30, idle = true
        ))
        assertTrue(FramePacingPolicy.shouldEncode(
            1_500_000_000L, 1_000_000_000L, 30, idle = true
        ))
    }

    @Test
    fun captureClockRestartDoesNotFreezeEncoding() {
        assertTrue(FramePacingPolicy.shouldEncode(10L, 9_000_000_000L, 30, idle = false))
    }
}
