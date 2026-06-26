package com.opencambridge.android.state

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentLinkedDeque

@Serializable
data class LogEntry(
    val timestamp: Long,
    val level: String, // INFO, WARN, ERROR
    val source: String, // Camera, API, Security, UI, System
    val message: String
)

object AppLogger {
    private const val MAX_LOGS = 200
    private val logs = ConcurrentLinkedDeque<LogEntry>()

    fun i(source: String, message: String) {
        addLog("INFO", source, message)
    }

    fun w(source: String, message: String) {
        addLog("WARN", source, message)
    }

    fun e(source: String, message: String) {
        addLog("ERROR", source, message)
    }

    private fun addLog(level: String, source: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            source = source,
            message = message
        )
        logs.addFirst(entry)
        while (logs.size > MAX_LOGS) {
            logs.pollLast()
        }
    }

    fun getLogs(): List<LogEntry> {
        return logs.toList()
    }

    fun clear() {
        logs.clear()
    }
}
