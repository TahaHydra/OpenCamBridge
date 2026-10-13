package com.opencambridge.android.service

import com.opencambridge.android.state.LifecycleState

internal object H264RuntimeRecoveryPolicy {
    fun active(state: LifecycleState, rebinding: Boolean, mode: String): Boolean =
        state == LifecycleState.STREAMING && !rebinding && mode == "h264"
    fun current(eventGeneration: Long, generation: Long, state: LifecycleState, rebinding: Boolean, mode: String): Boolean =
        eventGeneration == generation && active(state, rebinding, mode)
}
