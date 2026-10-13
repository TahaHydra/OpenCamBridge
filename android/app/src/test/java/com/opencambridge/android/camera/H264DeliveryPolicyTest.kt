package com.opencambridge.android.camera

import org.junit.Assert.*
import org.junit.Test

class H264DeliveryPolicyTest {
    @Test fun profilesBoundAgeAndReserveMetadata() {
        assertEquals(120L, H264DeliveryPolicy.maxAgeMs("low"))
        assertEquals(250L, H264DeliveryPolicy.maxAgeMs("balanced"))
        assertEquals(500L, H264DeliveryPolicy.maxAgeMs("smooth"))
        assertEquals(11, H264DeliveryPolicy.capacity(60, "low"))
        assertEquals(18, H264DeliveryPolicy.capacity(60, "balanced"))
        assertEquals(33, H264DeliveryPolicy.capacity(60, "smooth"))
    }
    @Test fun onlyActualIdrRestartsReferenceChain() {
        assertFalse(H264DeliveryPolicy.containsIdr(byteArrayOf(0, 0, 1, 0x61, 1)))
        assertTrue(H264DeliveryPolicy.containsIdr(byteArrayOf(0, 0, 0, 1, 0x65, 1)))
        assertTrue(H264DeliveryPolicy.containsIdr(byteArrayOf(0, 0, 1, 0x67, 1, 0, 0, 1, 0x65)))
        assertFalse(H264DeliveryPolicy.containsIdr(byteArrayOf(0, 0, 1)))
    }
    @Test fun recoveryRequestsCoalesceAndRespectCooldown() {
        val policy = H264IdrPolicy()
        assertEquals(0L, policy.request(100))
        assertNull(policy.request(101))
        assertTrue(policy.fire(100))
        assertEquals(400L, policy.request(200))
        assertNull(policy.request(250))
        policy.onIdr()
        assertFalse(policy.fire(600))
        assertEquals(0L, policy.request(700))
        assertTrue(policy.fire(700))
        policy.reset()
        assertEquals(0L, policy.request(701))
    }
    @Test fun recoveryInvalidatesDequeuedReferencesUntilGenuineIdr() {
        val client = H264ClientDeliveryState(120)
        val old = byteArrayOf(1)
        assertFalse(client.acceptsVideo(false))
        assertTrue(client.acceptsVideo(true))
        client.enqueue(old, 0)
        assertTrue(client.expired(121))
        assertTrue(client.recover(121))
        assertFalse(client.consume(old, 121))
        assertFalse(client.acceptsVideo(false))
        assertTrue(client.acceptsVideo(true))
        val fresh = byteArrayOf(2)
        client.enqueue(fresh, 122)
        assertTrue(client.consume(fresh, 123))
        assertFalse(client.consume(fresh, 124))
    }
    @Test fun repeatedRecoveryBudgetHasTimeWindowInsteadOfResettingOnOneSend() {
        val client = H264ClientDeliveryState(250)
        assertTrue(client.recover(0))
        assertTrue(client.recover(100))
        assertTrue(client.recover(200))
        assertFalse(client.recover(300))
        assertTrue(client.recover(10_301))
    }
}
