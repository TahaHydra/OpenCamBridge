package com.opencambridge.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class H264ClientQueuePolicyTest {
    @Test
    fun buffers_half_a_second_plus_stream_metadata() {
        assertEquals(17, h264ClientQueueCapacity(30))
        assertEquals(32, h264ClientQueueCapacity(60))
    }

    @Test
    fun keeps_invalid_or_extreme_rates_bounded() {
        assertEquals(10, h264ClientQueueCapacity(0))
        assertEquals(32, h264ClientQueueCapacity(240))
    }
}
