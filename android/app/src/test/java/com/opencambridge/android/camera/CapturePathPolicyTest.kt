package com.opencambridge.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun sevenTwentySixtyNeverUpgradesToTenEightySixty() {
        val requested = H264ModeDto(1280, 720, 60)
        assertEquals(
            listOf(H264ModeDto(1280, 720, 60), H264ModeDto(1280, 720, 30)),
            CapturePathPolicy.adaptiveModes(requested, H264Capabilities.preferredModes)
        )
    }

    @Test
    fun sevenTwentyThirtyNeverUpgradesToTenEightyThirty() {
        val requested = H264ModeDto(1280, 720, 30)
        assertEquals(
            listOf(requested),
            CapturePathPolicy.adaptiveModes(requested, H264Capabilities.preferredModes)
        )
    }

    @Test
    fun explicitProfileTriesOnlyTheRequestedTuple() {
        val requested = H264ModeDto(1920, 1080, 60)
        assertEquals(
            listOf(requested),
            CapturePathPolicy.candidateModes("quality", requested, H264Capabilities.preferredModes)
        )
        assertEquals(
            listOf(requested),
            CapturePathPolicy.candidateModes("native", requested, H264Capabilities.preferredModes)
        )
    }

    @Test
    fun adaptiveOrderIsExactAndOnlyDowngrades() {
        val requested = H264ModeDto(1920, 1080, 60)
        val candidates = CapturePathPolicy.candidateModes("adaptive", requested, H264Capabilities.preferredModes)
        assertEquals(
            listOf(
                H264ModeDto(1920, 1080, 60),
                H264ModeDto(1280, 720, 60),
                H264ModeDto(1920, 1080, 30),
                H264ModeDto(1280, 720, 30)
            ),
            candidates
        )
        assertTrue(candidates.all { it.width <= requested.width && it.height <= requested.height && it.fps <= requested.fps })
    }

    @Test
    fun h264SixtyFallbackSelectsCanonicalMjpegThirty() {
        val selected = CapturePathPolicy.selectMjpegFallback(
            H264ModeDto(1920, 1080, 60),
            listOf(H264ModeDto(1280, 720, 30), H264ModeDto(1920, 1080, 30))
        )
        assertEquals(H264ModeDto(1920, 1080, 30), selected)
        assertFalse(selected?.fps == 60)
    }

    @Test
    fun mjpegNeverAdvertisesSixtyOnAThirtyFpsPath() {
        assertEquals(listOf(15, 30), CapturePathPolicy.selectableMjpegFps(30))
    }

    @Test
    fun mjpegAdvertisesSixtyOnlyWhenTheRegularPathReportsIt() {
        assertEquals(listOf(15, 30, 60), CapturePathPolicy.selectableMjpegFps(60))
    }
}
