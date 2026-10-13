package com.opencambridge.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class H264ClientQueuePolicyTest {
    @Test
    fun defaults_to_balanced_age_budget_plus_stream_metadata() {
        assertEquals(11, h264ClientQueueCapacity(30))
        assertEquals(18, h264ClientQueueCapacity(60))
        assertEquals(33, h264ClientQueueCapacity(60, "smooth"))
    }

    @Test
    fun keeps_invalid_or_extreme_rates_bounded() {
        assertEquals(6, h264ClientQueueCapacity(0))
        assertEquals(63, h264ClientQueueCapacity(240))
    }
}
