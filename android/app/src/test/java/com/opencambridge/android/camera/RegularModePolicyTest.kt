package com.opencambridge.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegularModePolicyTest {
    @Test
    fun onePlusStyle1080pDurationOnlyOffersThirty() {
        val modes = RegularModePolicy.build(
            "0",
            "MEDIA_CODEC_SURFACE",
            listOf(RegularModePolicy.Output(1920, 1080, 33_333_333)),
            listOf(RegularModePolicy.AeRange(15, 30), RegularModePolicy.AeRange(30, 30)),
        )
        assertEquals(listOf(15, 30), modes.map { it.fps }.sorted())
        assertFalse(modes.any { it.fps == 60 })
    }

    @Test
    fun samsungStyleExactTupleOffersSixty() {
        val modes = RegularModePolicy.build(
            "2",
            "MEDIA_CODEC_SURFACE",
            listOf(RegularModePolicy.Output(1920, 1080, 16_666_667)),
            listOf(RegularModePolicy.AeRange(30, 30), RegularModePolicy.AeRange(30, 60)),
        )
        val sixty = modes.single { it.fps == 60 }
        assertEquals("2", sixty.cameraId)
        assertEquals("MEDIA_CODEC_SURFACE", sixty.outputFormat)
        assertEquals(30, sixty.aeFpsMin)
        assertEquals(60, sixty.aeFpsMax)
    }

    @Test
    fun cameraWideSixtyDoesNotOverrideSlowResolutionDuration() {
        val modes = RegularModePolicy.build(
            "0",
            "YUV_420_888",
            listOf(
                RegularModePolicy.Output(1280, 720, 16_666_667),
                RegularModePolicy.Output(1920, 1080, 33_333_333),
            ),
            listOf(RegularModePolicy.AeRange(15, 60)),
        )
        assertTrue(modes.any { it.width == 1280 && it.fps == 60 })
        assertFalse(modes.any { it.width == 1920 && it.fps == 60 })
    }

    @Test
    fun unknownMinimumDurationIsNotAdvertised() {
        val modes = RegularModePolicy.build(
            "0",
            "YUV_420_888",
            listOf(RegularModePolicy.Output(1920, 1080, 0)),
            listOf(RegularModePolicy.AeRange(15, 60)),
        )
        assertTrue(modes.isEmpty())
    }
}
