package com.opencambridge.android.camera

internal data class Nv21Dimensions(val width: Int, val height: Int)

/** Source-authoritative NV21 transform used by the MJPEG path before JPEG
 * encoding. Rotation is clockwise and mirror is horizontal in the rotated
 * output coordinate system. Chroma VU pairs are never split. */
internal object Nv21Transform {
    fun outputDimensions(width: Int, height: Int, rotation: Int): Nv21Dimensions {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(rotation in setOf(0, 90, 180, 270))
        return if (rotation % 180 == 0) Nv21Dimensions(width, height)
        else Nv21Dimensions(height, width)
    }

    fun transform(
        src: ByteArray,
        dst: ByteArray,
        scratch: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        mirror: Boolean
    ): Nv21Dimensions {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(rotation in setOf(0, 90, 180, 270))
        val size = width * height * 3 / 2
        require(src.size >= size && dst.size >= size && scratch.size >= size)
        require(src !== dst && src !== scratch && dst !== scratch)

        val dimensions = outputDimensions(width, height, rotation)
        val rotatedWidth = dimensions.width
        val rotatedHeight = dimensions.height
        when {
            rotation == 0 && mirror -> mirrorHorizontal(src, dst, rotatedWidth, rotatedHeight)
            rotation == 0 -> System.arraycopy(src, 0, dst, 0, size)
            mirror -> {
                rotate(src, scratch, width, height, rotation)
                mirrorHorizontal(scratch, dst, rotatedWidth, rotatedHeight)
            }
            // Rotation can write directly into the output when no mirror
            // follows. The previous scratch->dst copy moved another full
            // 3.1 MB for every rotated 1080p frame.
            else -> rotate(src, dst, width, height, rotation)
        }
        return dimensions
    }

    private fun mirrorHorizontal(src: ByteArray, dst: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        for (row in 0 until height) {
            val base = row * width
            for (x in 0 until width) dst[base + x] = src[base + width - 1 - x]
        }
        for (row in 0 until height / 2) {
            val base = ySize + row * width
            for (x in 0 until width step 2) {
                val sourcePair = base + width - 2 - x
                dst[base + x] = src[sourcePair]
                dst[base + x + 1] = src[sourcePair + 1]
            }
        }
    }

    private fun rotate(src: ByteArray, dst: ByteArray, width: Int, height: Int, degrees: Int) {
        val ySize = width * height
        val total = ySize + ySize / 2
        when (degrees) {
            90 -> {
                var i = 0
                for (x in 0 until width) for (y in height - 1 downTo 0) dst[i++] = src[y * width + x]
                i = ySize
                for (x in 0 until width step 2) for (y in height / 2 - 1 downTo 0) {
                    val p = ySize + y * width + x
                    dst[i++] = src[p]
                    dst[i++] = src[p + 1]
                }
            }
            180 -> {
                var i = 0
                for (p in ySize - 1 downTo 0) dst[i++] = src[p]
                i = ySize
                var p = total - 2
                while (p >= ySize) {
                    dst[i++] = src[p]
                    dst[i++] = src[p + 1]
                    p -= 2
                }
            }
            270 -> {
                var i = 0
                for (x in width - 1 downTo 0) for (y in 0 until height) dst[i++] = src[y * width + x]
                i = ySize
                for (x in width - 2 downTo 0 step 2) for (y in 0 until height / 2) {
                    val p = ySize + y * width + x
                    dst[i++] = src[p]
                    dst[i++] = src[p + 1]
                }
            }
        }
    }
}
