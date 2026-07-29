package com.opencambridge.android.camera

import android.media.MediaFormat

/** Colour description sent with every H.264 stream generation. Values use
 * stable protocol names rather than Android integer constants. */
internal data class VideoColourInfo(
    val matrix: String,
    val range: String,
    val primaries: String,
    val transfer: String,
) {
    companion object {
        fun regularSdr(width: Int): VideoColourInfo =
            if (width >= 1280) {
                VideoColourInfo("bt709", "limited", "bt709", "bt709")
            } else {
                VideoColourInfo("bt601", "limited", "bt601", "bt709")
            }

        fun fromMediaFormat(format: MediaFormat, width: Int): VideoColourInfo {
            val fallback = regularSdr(width)
            fun integer(key: String): Int? =
                try { if (format.containsKey(key)) format.getInteger(key) else null }
                catch (_: Exception) { null }
            val standard = integer(MediaFormat.KEY_COLOR_STANDARD)
            val colorRange = integer(MediaFormat.KEY_COLOR_RANGE)
            val colorTransfer = integer(MediaFormat.KEY_COLOR_TRANSFER)
            val matrix = when (standard) {
                MediaFormat.COLOR_STANDARD_BT709 -> "bt709"
                MediaFormat.COLOR_STANDARD_BT601_PAL,
                MediaFormat.COLOR_STANDARD_BT601_NTSC -> "bt601"
                MediaFormat.COLOR_STANDARD_BT2020 -> "bt2020"
                else -> fallback.matrix
            }
            val range = when (colorRange) {
                MediaFormat.COLOR_RANGE_FULL -> "full"
                MediaFormat.COLOR_RANGE_LIMITED -> "limited"
                else -> fallback.range
            }
            val transfer = when (colorTransfer) {
                MediaFormat.COLOR_TRANSFER_LINEAR -> "linear"
                MediaFormat.COLOR_TRANSFER_ST2084 -> "st2084"
                MediaFormat.COLOR_TRANSFER_HLG -> "hlg"
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "bt709"
                else -> fallback.transfer
            }
            return VideoColourInfo(matrix, range, matrix, transfer)
        }
    }
}
