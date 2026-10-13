package com.opencambridge.android.service

import com.opencambridge.android.state.LifecycleState
import org.junit.Assert.*
import org.junit.Test

class H264RuntimeRecoveryPolicyTest {
    @Test fun startupOwnsItsFailureFlagUntilItsCandidateFallbackCompletes() {
        assertFalse(H264RuntimeRecoveryPolicy.active(LifecycleState.STARTING, true, "h264"))
        assertFalse(H264RuntimeRecoveryPolicy.active(LifecycleState.RECONFIGURING, true, "h264"))
        assertFalse(H264RuntimeRecoveryPolicy.active(LifecycleState.STREAMING, true, "h264"))
        assertTrue(H264RuntimeRecoveryPolicy.active(LifecycleState.STREAMING, false, "h264"))
        assertFalse(H264RuntimeRecoveryPolicy.active(LifecycleState.STREAMING, false, "mjpeg"))
    }
    @Test fun queuedFailureFromAnEarlierPipelineCannotReplaceRecoveredH264() {
        assertFalse(H264RuntimeRecoveryPolicy.current(4, 5, LifecycleState.STREAMING, false, "h264"))
        assertFalse(H264RuntimeRecoveryPolicy.current(5, 5, LifecycleState.STARTING, false, "h264"))
        assertTrue(H264RuntimeRecoveryPolicy.current(5, 5, LifecycleState.STREAMING, false, "h264"))
    }
}
