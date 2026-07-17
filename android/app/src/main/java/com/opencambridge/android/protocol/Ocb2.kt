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

    data class Record(
        val type: Short,
        val flags: Int,
        val sequence: Long,
        val captureTimestampNs: Long,
        val encoderTimestampUs: Long,
        val payload: ByteArray
    )

    class ParseException(val code: String, message: String) : IllegalArgumentException(message)

    /** Incremental reference parser used by Kotlin conformance tests and diagnostics. */
    class Parser(initialCapacity: Int = 128 * 1024) {
        private var bytes = ByteArray(initialCapacity)
        private var length = 0

        fun reset() {
            length = 0
        }

        fun push(input: ByteArray, offset: Int = 0, count: Int = input.size - offset) {
            require(offset >= 0 && count >= 0 && offset + count <= input.size)
            ensureCapacity(length + count)
            input.copyInto(bytes, length, offset, offset + count)
            length += count
        }

        fun next(): Record? {
            if (length < HEADER_SIZE) return null
            if (bytes[0] != 'O'.code.toByte() || bytes[1] != 'C'.code.toByte() ||
                bytes[2] != 'B'.code.toByte() || bytes[3] != '2'.code.toByte()
            ) throw ParseException("BAD_MAGIC", "OCB2 magic mismatch")

            val header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            header.position(4)
            val version = header.short.toInt() and 0xffff
            if (version != VERSION.toInt()) {
                throw ParseException("UNSUPPORTED_VERSION", "unsupported OCB2 version $version")
            }
            val headerSize = header.short.toInt() and 0xffff
            if (headerSize != HEADER_SIZE.toInt()) {
                throw ParseException("INVALID_HEADER_SIZE", "invalid OCB2 header size $headerSize")
            }
            val type = header.short
            if (type !in TYPE_STREAM_INFO..TYPE_ERROR) {
                throw ParseException("INVALID_RECORD_TYPE", "invalid OCB2 record type $type")
            }
            header.short // reserved
            val flags = header.int
            val sequence = header.long
            val captureTimestampNs = header.long
            val encoderTimestampUs = header.long
            val payloadLength = header.int.toLong() and 0xffff_ffffL
            if (payloadLength > MAX_PAYLOAD_SIZE) {
                throw ParseException("PAYLOAD_TOO_LARGE", "OCB2 payload exceeds canonical limit")
            }
            val total = HEADER_SIZE.toLong() + payloadLength
            if (total > Int.MAX_VALUE || length.toLong() < total) return null
            val totalInt = total.toInt()
            val payload = bytes.copyOfRange(HEADER_SIZE.toInt(), totalInt)
            val remaining = length - totalInt
            if (remaining > 0) bytes.copyInto(bytes, 0, totalInt, length)
            length = remaining
            return Record(type, flags, sequence, captureTimestampNs, encoderTimestampUs, payload)
        }

        private fun ensureCapacity(required: Int) {
            if (required <= bytes.size) return
            var capacity = bytes.size.coerceAtLeast(1)
            while (capacity < required) capacity = (capacity * 2).coerceAtLeast(required)
            bytes = bytes.copyOf(capacity)
        }
    }

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
