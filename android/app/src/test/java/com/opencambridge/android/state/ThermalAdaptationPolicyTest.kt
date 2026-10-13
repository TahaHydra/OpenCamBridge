package com.opencambridge.android.state

import org.junit.Assert.*
import org.junit.Test

class ThermalAdaptationPolicyTest {
    @Test fun sustainedHeatReducesBitrateBeforeFpsAndResolution() {
        val policy = ThermalAdaptationPolicy()
        assertEquals(0, policy.evaluate(3, 0, true).level)
        assertEquals(0, policy.evaluate(3, 9_999, true).level)
        val first = policy.evaluate(3, 10_000, true)
        assertEquals(0.85, first.bitrateScale, 0.001)
        assertNull(first.fpsCap)
        val second = policy.evaluate(3, 20_000, true)
        assertEquals(0.70, second.bitrateScale, 0.001)
        assertNull(second.fpsCap)
        assertEquals(2, policy.evaluate(3, 49_999, true).level)
        val third = policy.evaluate(3, 50_000, true)
        assertEquals(30, third.fpsCap)
        assertEquals(0, third.cameraStep)
        assertEquals(1, policy.evaluate(3, 80_000, true).cameraStep)
        assertEquals(2, policy.evaluate(3, 110_000, true).cameraStep)
        assertEquals(5, policy.evaluate(6, 1_000_000, true).level)
    }

    @Test fun transientHeatAndModerateHoldBandDoNotOscillate() {
        val policy = ThermalAdaptationPolicy()
        policy.evaluate(3, 0, true)
        policy.evaluate(2, 9_000, true)
        assertEquals(0, policy.evaluate(3, 10_000, true).level)
        assertEquals(1, policy.evaluate(3, 20_000, true).level)
        assertEquals(1, policy.evaluate(2, 30_000, true).level)
        assertEquals(1, policy.evaluate(2, 900_000, true).level)
    }

    @Test fun recoveryRequiresFullCoolDwellAndRestoresOneStepAtATime() {
        val policy = ThermalAdaptationPolicy()
        policy.evaluate(3, 0, true)
        listOf(10_000L, 20_000L, 50_000L, 80_000L, 110_000L).forEach { policy.evaluate(3, it, true) }
        assertEquals(5, policy.evaluate(1, 120_000, true).level)
        assertEquals(5, policy.evaluate(1, 179_999, true).level)
        assertEquals(4, policy.evaluate(1, 180_000, true).level)
        assertEquals(4, policy.evaluate(1, 209_999, true).level)
        assertEquals(3, policy.evaluate(1, 210_000, true).level)
        assertEquals(2, policy.evaluate(1, 240_000, true).level)
        assertEquals(1, policy.evaluate(1, 270_000, true).level)
        assertEquals(0, policy.evaluate(1, 300_000, true).level)
        assertEquals(1.0, policy.current().bitrateScale, 0.0)
        assertNull(policy.current().fpsCap)
    }

    @Test fun moderateOrHotReturnRestartsRecoveryDwell() {
        val policy = ThermalAdaptationPolicy()
        policy.evaluate(3, 0, true)
        policy.evaluate(3, 10_000, true)
        policy.evaluate(0, 20_000, true)
        policy.evaluate(2, 79_000, true)
        assertEquals(1, policy.evaluate(0, 80_000, true).level)
        assertEquals(1, policy.evaluate(0, 139_999, true).level)
        assertEquals(0, policy.evaluate(0, 140_000, true).level)
    }

    @Test fun manualQualityCannotBeAdaptedAndResetsRecoveryState() {
        val policy = ThermalAdaptationPolicy()
        policy.evaluate(4, 0, true)
        assertEquals(1, policy.evaluate(4, 5_000, true).level)
        val manual = policy.evaluate(6, 6_000, false)
        assertEquals(0, manual.level)
        assertEquals(1.0, manual.bitrateScale, 0.0)
        assertNull(manual.fpsCap)
        assertEquals(0, policy.evaluate(6, 1_000_000, false).cameraStep)
        assertEquals(0, policy.evaluate(3, 1_000_001, true).level)
    }

    @Test fun wifiLockRequiresEveryPlatformAndStreamCondition() {
        fun eligible(api: Int = 29, streaming: Boolean = true, foreground: Boolean = true,
                     interactive: Boolean = true, wifi: Boolean = true, lan: Boolean = true,
                     clients: Int = 1, profile: String = "balanced") =
            WifiLatencyLockPolicy.eligible(api, streaming, foreground, interactive, wifi, lan, clients, profile)
        assertTrue(eligible())
        assertTrue(eligible(profile = "low"))
        assertFalse(eligible(api = 28))
        assertFalse(eligible(streaming = false))
        assertFalse(eligible(foreground = false))
        assertFalse(eligible(interactive = false))
        assertFalse(eligible(wifi = false))
        assertFalse(eligible(lan = false))
        assertFalse(eligible(clients = 0))
        assertFalse(eligible(profile = "smooth"))
    }
}
