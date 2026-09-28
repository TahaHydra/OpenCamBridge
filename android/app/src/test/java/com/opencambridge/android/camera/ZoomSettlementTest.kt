package com.opencambridge.android.camera

import org.junit.Assert.*
import org.junit.Test

class ZoomSettlementTest {
    @Test fun requestsOneRefreshOnlyForCapturedFinalZoom() {
        val settlement = ZoomSettlement()
        val move = settlement.begin()
        repeat(80) { assertFalse(settlement.onCaptured(null)) }
        assertTrue(settlement.onCaptured(move))
        repeat(10) { assertFalse(settlement.onCaptured(move)) }
    }

    @Test fun newMoveAndStopInvalidateOldFinalCapture() {
        val settlement = ZoomSettlement()
        val oldMove = settlement.begin()
        val latestMove = settlement.begin()
        assertFalse(settlement.onCaptured(oldMove))
        assertTrue(settlement.onCaptured(latestMove))
        val stoppedMove = settlement.begin()
        settlement.cancel()
        assertFalse(settlement.onCaptured(stoppedMove))
    }
}
