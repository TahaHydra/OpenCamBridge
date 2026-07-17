package com.opencambridge.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CapturePathPolicyTest {
    @Test
    fun oneHundredTwentyFpsInputBridgesToSixty() {
        assertEquals(120 to 120, CapturePathPolicy.bridgeRange(listOf(120 to 120, 240 to 240), 60))
        assertNull(CapturePathPolicy.directRange(listOf(120 to 120, 240 to 240), 60))
    }

    @Test
    fun directSixtyIsPreferredWhenDeclared() {
        assertEquals(60 to 60, CapturePathPolicy.directRange(listOf(30 to 60, 60 to 60, 120 to 120), 60))
        assertEquals(60 to 60, CapturePathPolicy.bridgeRange(listOf(60 to 60, 120 to 120), 60))
    }

    @Test
    fun selectingThirtyNeverSilentlyAdaptsUpToSixty() {
        val requested = H264ModeDto(1920, 1080, 30)
        val candidates = CapturePathPolicy.adaptiveModes(requested, H264Capabilities.preferredModes)
        assertEquals(requested, candidates.first())
        assertFalse(candidates.any { it.fps > 30 })
    }

    @Test
    fun sixtyAdaptiveOrderMatchesProductPreference() {
        val requested = H264ModeDto(1920, 1080, 60)
        assertEquals(H264Capabilities.preferredModes, CapturePathPolicy.adaptiveModes(requested, H264Capabilities.preferredModes))
    }
}
