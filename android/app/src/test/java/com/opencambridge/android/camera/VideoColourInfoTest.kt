package com.opencambridge.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoColourInfoTest {
    @Test
    fun hdRegularSdrUsesBt709Limited() {
        assertEquals(
            VideoColourInfo("bt709", "limited", "bt709", "bt709"),
            VideoColourInfo.regularSdr(1920),
        )
    }

    @Test
    fun sdRegularSdrUsesBt601Limited() {
        assertEquals(
            VideoColourInfo("bt601", "limited", "bt601", "bt709"),
            VideoColourInfo.regularSdr(640),
        )
    }
}
