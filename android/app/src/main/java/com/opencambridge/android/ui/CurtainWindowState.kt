package com.opencambridge.android.ui

internal data class CurtainWindowState(
    val brightness: Float,
    val visibleBars: Int?,
    val barsBehavior: Int,
)

internal interface CurtainWindow {
    var state: CurtainWindowState
    fun dim()
}

/** Owns only the temporary window changes made by one curtain appearance. */
internal class CurtainWindowLease(private val window: CurtainWindow) {
    private val original = window.state
    private var restored = false
    init { window.dim() }
    fun restore() {
        if (restored) return
        window.state = original
        restored = true
    }
}
