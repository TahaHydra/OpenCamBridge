package com.opencambridge.android.camera

/** Tracks the final Camera2 request of each zoom movement. */
internal class ZoomSettlement {
    private var generation = 0L
    private var pending: Long? = null

    @Synchronized fun begin(): Long = (++generation).also { pending = it }
    @Synchronized fun cancel() { generation++; pending = null }
    @Synchronized fun isCurrent(tag: Long): Boolean = tag == generation

    @Synchronized fun onCaptured(tag: Long?): Boolean {
        if (tag == null || tag != pending) return false
        pending = null
        return true
    }
}
