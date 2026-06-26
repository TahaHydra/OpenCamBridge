package com.opencambridge.android.state

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared mutable state for the stream. Accessed from both the UI thread and
 * Ktor server coroutines — uses atomic types for thread safety.
 */
object StreamState {
    val streaming = AtomicBoolean(false)
    val cameraId = AtomicReference("0")
    val width = AtomicInteger(1280)
    val height = AtomicInteger(720)
    val jpegQuality = AtomicInteger(85)

    /** Latest JPEG frame bytes, updated by MjpegStreamer. Null before first frame. */
    val latestFrame = AtomicReference<ByteArray?>(null)

    fun toStatusDto(): StreamStatusDto = StreamStatusDto(
        streaming = streaming.get(),
        cameraId = cameraId.get(),
        width = width.get(),
        height = height.get(),
        jpegQuality = jpegQuality.get()
    )
}

@Serializable
data class StreamStatusDto(
    val streaming: Boolean,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val jpegQuality: Int
)
