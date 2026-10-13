package com.opencambridge.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class H264EncoderPolicyTest {
    @Test fun platformPriorityWinsOverAlphabeticCodecNameAndMissingPointsStayEligible() {
        fun encoder(name: String, covered: Boolean? = null) =
            H264EncoderPolicy.Evidence(name, true, false, true, true, covered)
        assertEquals(listOf("vendor.z", "vendor.a", "legacy"), H264EncoderPolicy.rank(
            listOf(encoder("legacy"), encoder("vendor.z", true), encoder("vendor.a", true)))
            .map { it.name })
        assertEquals(listOf("z", "a"), H264EncoderPolicy.rank(listOf(encoder("z"), encoder("a", false))).map { it.name })
    }
    @Test fun actualHardwarePreferredAndUnsupportedSurfaceRejected() {
        assertEquals(listOf("hardware", "unknown"), H264EncoderPolicy.rank(listOf(
            H264EncoderPolicy.Evidence("unknown", false, false, true, true, null),
            H264EncoderPolicy.Evidence("hardware", true, false, true, true, null),
            H264EncoderPolicy.Evidence("software", false, true, true, true, true),
            H264EncoderPolicy.Evidence("bytes", true, false, false, true, true)))
            .map { it.name })
    }
}
