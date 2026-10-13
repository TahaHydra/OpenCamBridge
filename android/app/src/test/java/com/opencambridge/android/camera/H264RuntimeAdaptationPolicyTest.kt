package com.opencambridge.android.camera

import org.junit.Assert.*
import org.junit.Test

class H264RuntimeAdaptationPolicyTest {
    @Test fun effectiveCaptureCadenceIsAdvertisedWithoutRaisingLowerSourceRates() {
        assertEquals(30, H264RuntimeAdaptationPolicy.streamFps(60, 30))
        assertEquals(24, H264RuntimeAdaptationPolicy.streamFps(24, 30))
        assertEquals(60, H264RuntimeAdaptationPolicy.streamFps(60, null))
    }
    @Test fun thermalResolutionStepsNeverIncreasePixelsAtCappedCadence() {
        val full60 = H264ModeDto(1920, 1080, 60)
        val hd60 = H264ModeDto(1280, 720, 60)
        val full30 = H264ModeDto(1920, 1080, 30)
        val hd30 = H264ModeDto(1280, 720, 30)
        val vga30 = H264ModeDto(640, 480, 30)
        val ladder = listOf(full60, hd60, full30, hd30, vga30)
        assertEquals(hd30, H264RuntimeAdaptationPolicy.thermalMode(full60, ladder, 30))
        assertEquals(vga30, H264RuntimeAdaptationPolicy.thermalMode(hd60, ladder, 30))
        assertEquals(vga30, H264RuntimeAdaptationPolicy.thermalMode(hd30, ladder, 30))
        assertEquals(null, H264RuntimeAdaptationPolicy.thermalMode(vga30, ladder, 30))
    }

    @Test fun thermalFallbackNeverRaisesAnAlreadyLowerRequestedCadence() {
        val current = H264ModeDto(1920, 1080, 24)
        val lower30 = H264ModeDto(1280, 720, 30)
        val lower15 = H264ModeDto(1280, 720, 15)
        assertEquals(lower15, H264RuntimeAdaptationPolicy.thermalMode(current, listOf(lower30, lower15), 30))
    }
    @Test fun manualCameraOrBitratePreservesRequestedQuality() {
        assertEquals(H264RuntimeAdaptationPolicy.Effective(1.0, null),
            H264RuntimeAdaptationPolicy.resolve("manual", "auto", 0.7, 30, 60))
        assertEquals(H264RuntimeAdaptationPolicy.Effective(1.0, null),
            H264RuntimeAdaptationPolicy.resolve("adaptive", "manual", 0.7, 30, 60))
    }
    @Test fun automaticControlsAreBoundedAndRecoverToRequestedDefaults() {
        assertEquals(H264RuntimeAdaptationPolicy.Effective(0.7, 30),
            H264RuntimeAdaptationPolicy.resolve("adaptive", "auto", 0.7, 30, 60))
        assertEquals(H264RuntimeAdaptationPolicy.Effective(0.25, 25),
            H264RuntimeAdaptationPolicy.resolve("adaptive", "auto", 0.1, 30, 25))
        assertEquals(H264RuntimeAdaptationPolicy.Effective(1.0, null),
            H264RuntimeAdaptationPolicy.resolve("adaptive", "auto", Double.NaN, null, 60))
    }
}
