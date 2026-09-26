package com.opencambridge.android.state
import org.junit.Assert.assertEquals
import org.junit.Test
class H264BitratePolicyTest {
    @Test fun automaticUsesActualModeAndDeviceLimits() {
        assertEquals(10_000_000, H264BitratePolicy.resolve("auto", 4_000_000, 1920, 1080, 30, 1, 30_000_000))
        assertEquals(16_000_000, H264BitratePolicy.resolve("auto", 4_000_000, 1920, 1080, 60, 1, 30_000_000))
        assertEquals(6_000_000, H264BitratePolicy.resolve("auto", 20_000_000, 1280, 720, 30, 1, 30_000_000))
        assertEquals(8_000_000, H264BitratePolicy.resolve("auto", 4_000_000, 1920, 1080, 60, 1, 8_000_000))
    }
    @Test fun manualIsNotRaisedToAutomaticFloorAndRespectsCapabilities() {
        assertEquals(4_000_000, H264BitratePolicy.resolve("manual", 4_000_000, 1920, 1080, 60, 1, 30_000_000))
        assertEquals(2_000_000, H264BitratePolicy.resolve("manual", 1_000_000, 1280, 720, 30, 2_000_000, 8_000_000))
        assertEquals(8_000_000, H264BitratePolicy.resolve("manual", 20_000_000, 1280, 720, 30, 1, 8_000_000))
    }
}
