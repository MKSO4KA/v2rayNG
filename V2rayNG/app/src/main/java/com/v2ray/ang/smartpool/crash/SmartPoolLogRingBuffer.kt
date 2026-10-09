package com.v2ray.ang.smartpool.crash

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SmartPoolLogRingBuffer {
    // Учитываем рост Base64 в 4/3 (+33%): 44_000 * 1.33 ≈ 58_600 символов в броне Issue
    const val MAX_LOG_CHARS = 44_000
    private const val MAX_WINDOW_MS = 60_000L

    data class LogEntry(
        val timestampMs: Long,
        val message: String
    )

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private var currentChars = 0

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun log(msg: String) {
        val now = System.currentTimeMillis()
        val formatted = "[${synchronized(dateFormat) { dateFormat.format(Date(now))} }] $msg"
        val len = formatted.length

        synchronized(lock) {
            buffer.addLast(LogEntry(now, formatted))
            currentChars += len

            while (currentChars > MAX_LOG_CHARS && buffer.isNotEmpty()) {
                val removed = buffer.removeFirst()
                currentChars -= removed.message.length
            }
        }
    }

    fun getRecentLogs(windowMs: Long = MAX_WINDOW_MS): List<String> {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(lock) {
            return buffer.filter { it.timestampMs >= cutoff }.map { it.message }
        }
    }

    fun getAllBufferedLogs(): List<String> {
        synchronized(lock) {
            return buffer.map { it.message }
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            currentChars = 0
        }
    }
}
