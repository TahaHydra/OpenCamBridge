package com.opencambridge.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264SettingsPolicyTest {
    @Test
    fun keeps_intervals_inside_the_supported_range() {
        assertEquals(1, H264SettingsPolicy.normalizeKeyframeInterval(1))
        assertEquals(2, H264SettingsPolicy.normalizeKeyframeInterval(2))
        assertEquals(5, H264SettingsPolicy.normalizeKeyframeInterval(5))
        assertEquals(10, H264SettingsPolicy.normalizeKeyframeInterval(10))
    }

    @Test
    fun clamps_intervals_above_the_safety_net() {
        assertEquals(10, H264SettingsPolicy.normalizeKeyframeInterval(11))
        assertEquals(10, H264SettingsPolicy.normalizeKeyframeInterval(Int.MAX_VALUE))
    }

    @Test
    fun absent_or_corrupt_values_become_the_default_not_the_minimum() {
        // A stored 0 means "unset", not "keyframe every frame". Treating it as the
        // minimum would silently reinstate the 1 s GOP this policy moved away from.
        assertEquals(5, H264SettingsPolicy.normalizeKeyframeInterval(0))
        assertEquals(5, H264SettingsPolicy.normalizeKeyframeInterval(-1))
        assertEquals(5, H264SettingsPolicy.normalizeKeyframeInterval(Int.MIN_VALUE))
    }

    @Test
    fun the_default_is_inside_the_range_it_normalizes_to() {
        val default = H264SettingsPolicy.DEFAULT_KEYFRAME_INTERVAL_SECONDS
        assertTrue(default >= H264SettingsPolicy.MIN_KEYFRAME_INTERVAL_SECONDS)
        assertTrue(default <= H264SettingsPolicy.MAX_KEYFRAME_INTERVAL_SECONDS)
        assertEquals(default, H264SettingsPolicy.normalizeKeyframeInterval(default))
    }
}
