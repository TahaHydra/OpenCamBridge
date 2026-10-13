package com.opencambridge.android.camera

/** Shared latency profiles bound phone-side compressed backlog, in milliseconds.
 * Socket/kernel buffers are outside this queue; callers check again before writing. */
internal object H264DeliveryPolicy {
    fun maxAgeMs(profile: String): Long = when (profile) {
        "low" -> 120L
        "smooth" -> 500L
        else -> 250L
    }

    fun capacity(fps: Int, profile: String): Int =
        ((fps.coerceIn(1, 240) * maxAgeMs(profile) + 999) / 1000 + 3).toInt().coerceIn(6, 123)

    /** BUFFER_FLAG_KEY_FRAME can mark a non-IDR I picture. Only NAL type 5
     * starts a fresh AVC reference chain. Input must be normalized Annex B. */
    fun containsIdr(bytes: ByteArray): Boolean {
        var i = 0
        while (i + 3 < bytes.size) {
            val prefix = if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                when {
                    bytes[i + 2] == 1.toByte() -> 3
                    i + 4 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                    else -> 0
                }
            } else 0
            if (prefix > 0 && i + prefix < bytes.size && (bytes[i + prefix].toInt() and 31) == 5) return true
            i += if (prefix > 0) prefix else 1
        }
        return false
    }
}

/** One scheduled request coalesces simultaneous decoder recovery, join and zoom
 * events. Reset at each codec generation. An IDR received before a deferred
 * request cancels that request, because all current waiting clients see it. */
internal class H264IdrPolicy(private val minimumIntervalMs: Long = 500L) {
    private var lastRequestMs: Long? = null
    private var pending = false
    @Synchronized fun request(nowMs: Long): Long? {
        if (pending) return null
        pending = true
        return ((lastRequestMs?.plus(minimumIntervalMs) ?: nowMs) - nowMs).coerceAtLeast(0)
    }
    @Synchronized fun fire(nowMs: Long): Boolean {
        if (!pending) return false
        pending = false
        lastRequestMs = nowMs
        return true
    }
    @Synchronized fun onIdr() { pending = false }
    @Synchronized fun reset() { pending = false; lastRequestMs = null }
}

/** Accessed under the streamer's delivery lock, including the socket pre-write
 * check. Identity keys let an already-dequeued record be invalidated on reset. */
internal class H264ClientDeliveryState(val maxAgeMs: Long) {
    private val queued = LinkedHashMap<ByteArray, Long>()
    private val recoveries = java.util.ArrayDeque<Long>()
    var waitingForIdr = true
        private set
    fun enqueue(record: ByteArray, nowMs: Long) { queued[record] = nowMs }
    fun contains(record: ByteArray): Boolean = queued.containsKey(record)
    fun expired(nowMs: Long): Boolean = queued.values.firstOrNull()?.let { nowMs - it > maxAgeMs } ?: false
    fun consume(record: ByteArray, nowMs: Long): Boolean = queued.remove(record)?.let { nowMs - it <= maxAgeMs } ?: false
    fun acceptsVideo(idr: Boolean): Boolean {
        if (waitingForIdr && !idr) return false
        if (idr) waitingForIdr = false
        return true
    }
    fun recover(nowMs: Long): Boolean {
        restartChain()
        while (recoveries.isNotEmpty() && nowMs - recoveries.first > 10_000L) recoveries.removeFirst()
        recoveries.addLast(nowMs)
        return recoveries.size <= 3
    }
    fun restartChain() { queued.clear(); waitingForIdr = true }
}
