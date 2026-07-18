package com.opencambridge.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

class H264SettingsPolicyTest {
    @Test
    fun keeps_the_supported_interval() {
        assertEquals(1, H264SettingsPolicy.normalizeKeyframeInterval(1))
    }

    @Test
    fun migrates_legacy_and_invalid_intervals() {
        assertEquals(1, H264SettingsPolicy.normalizeKeyframeInterval(2))
        assertEquals(1, H264SettingsPolicy.normalizeKeyframeInterval(0))
        assertEquals(1, H264SettingsPolicy.normalizeKeyframeInterval(Int.MAX_VALUE))
    }
}
