package com.opencambridge.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenCamBridge streaming protocol V2. All integers are little-endian and every
 * record is self-delimiting, so TCP packet boundaries never affect decoding.
 */
object Ocb2 {
    const val VERSION: Short = 2
    const val HEADER_SIZE: Short = 48
    const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024

    const val TYPE_STREAM_INFO: Short = 1
    const val TYPE_CODEC_CONFIG: Short = 2
    const val TYPE_VIDEO_ACCESS_UNIT: Short = 3
    const val TYPE_HEARTBEAT: Short = 4
    const val TYPE_END_OF_STREAM: Short = 5
    const val TYPE_ERROR: Short = 6

    const val FLAG_CODEC_CONFIG = 1 shl 0
    const val FLAG_KEYFRAME = 1 shl 1
    const val FLAG_DISCONTINUITY = 1 shl 2
    const val FLAG_END_OF_STREAM = 1 shl 3

    fun record(
        type: Short,
        flags: Int,
        sequence: Long,
        captureTimestampNs: Long,
        encoderTimestampUs: Long,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD_SIZE) { "OCB2 payload is too large" }
        val out = ByteBuffer.allocate(HEADER_SIZE.toInt() + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.put(byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), '2'.code.toByte()))
        out.putShort(VERSION)
        out.putShort(HEADER_SIZE)
        out.putShort(type)
        out.putShort(0)
        out.putInt(flags)
        out.putLong(sequence)
        out.putLong(captureTimestampNs)
        out.putLong(encoderTimestampUs)
        out.putInt(payload.size)
        out.putInt(0)
        out.put(payload)
        return out.array()
    }
}
