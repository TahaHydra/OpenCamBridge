package com.opencambridge.android.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Nv21TransformTest {
    private val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 101, 102, 103, 104)

    private fun assertTransform(rotation: Int, mirror: Boolean, width: Int, height: Int, expected: ByteArray) {
        val output = ByteArray(source.size)
        val scratch = ByteArray(source.size)
        val dimensions = Nv21Transform.transform(source, output, scratch, 4, 2, rotation, mirror)
        assertEquals(width, dimensions.width)
        assertEquals(height, dimensions.height)
        assertArrayEquals(expected, output)
    }

    @Test fun rotation0MirrorOff() = assertTransform(0, false, 4, 2, source)
    @Test fun rotation0MirrorOn() = assertTransform(0, true, 4, 2,
        byteArrayOf(4, 3, 2, 1, 8, 7, 6, 5, 103, 104, 101, 102))
    @Test fun rotation90MirrorOff() = assertTransform(90, false, 2, 4,
        byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4, 101, 102, 103, 104))
    @Test fun rotation90MirrorOn() = assertTransform(90, true, 2, 4,
        byteArrayOf(1, 5, 2, 6, 3, 7, 4, 8, 101, 102, 103, 104))
    @Test fun rotation180MirrorOff() = assertTransform(180, false, 4, 2,
        byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1, 103, 104, 101, 102))
    @Test fun rotation180MirrorOn() = assertTransform(180, true, 4, 2,
        byteArrayOf(5, 6, 7, 8, 1, 2, 3, 4, 101, 102, 103, 104))
    @Test fun rotation270MirrorOff() = assertTransform(270, false, 2, 4,
        byteArrayOf(4, 8, 3, 7, 2, 6, 1, 5, 103, 104, 101, 102))
    @Test fun rotation270MirrorOn() = assertTransform(270, true, 2, 4,
        byteArrayOf(8, 4, 7, 3, 6, 2, 5, 1, 103, 104, 101, 102))
}
