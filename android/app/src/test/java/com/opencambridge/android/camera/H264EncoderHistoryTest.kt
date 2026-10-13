package com.opencambridge.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class H264EncoderHistoryTest {
    @Test fun actualSuccessOnlyReordersCodecsInsideItsExactTuple() {
        val history = H264EncoderHistory()
        val names = listOf("platform-first", "working")
        assertEquals(names, history.order("camera0:720p30:regular:osA", names))
        history.succeeded("camera0:720p30:regular:osA", "working")
        assertEquals(listOf("working", "platform-first"), history.order("camera0:720p30:regular:osA", names))
        for (other in listOf("camera1:720p30:regular:osA", "camera0:720p60:regular:osA", "camera0:720p30:regular:osB"))
            assertEquals(names, history.order(other, names))
    }

    @Test fun failedOrRemovedCodecDoesNotPoisonFallback() {
        val history = H264EncoderHistory()
        history.succeeded("tuple", "working")
        assertEquals(listOf("other"), history.order("tuple", listOf("other")))
        history.failed("tuple", "working")
        assertEquals(listOf("platform-first", "working"), history.order("tuple", listOf("platform-first", "working")))
    }
}
