package com.opencambridge.android.ui

import org.junit.Assert.*
import org.junit.Test

class CurtainWindowStateTest {
    @Test fun wakingRestoresExactBrightnessAndPartiallyHiddenSystemBars() {
        val original = CurtainWindowState(0.63f, 1, 0)
        val window = TestWindow(original)
        val curtain = CurtainWindowLease(window)
        assertEquals(CurtainWindowState(0f, 0, 2), window.state)
        curtain.restore()
        assertEquals(original, window.state)
    }

    @Test fun cleanupRestoresAutomaticBrightnessAndAlreadyHiddenBarsOnlyOnce() {
        val original = CurtainWindowState(-1f, 0, 1)
        val window = TestWindow(original)
        val curtain = CurtainWindowLease(window)
        curtain.restore()
        assertEquals(original, window.state)
        window.state = CurtainWindowState(0.8f, 3, 0)
        curtain.restore()
        assertEquals(CurtainWindowState(0.8f, 3, 0), window.state)
    }

    private class TestWindow(override var state: CurtainWindowState) : CurtainWindow {
        override fun dim() { state = CurtainWindowState(0f, 0, 2) }
    }
}
