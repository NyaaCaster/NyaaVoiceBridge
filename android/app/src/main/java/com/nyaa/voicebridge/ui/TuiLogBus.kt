package com.nyaa.voicebridge.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val tag: String, val colorHex: String) {
    INFO("INFO", "#00FF66"),      // 终端亮绿
    AUDIO("AUDIO", "#00E5FF"),    // 声学青蓝
    WARN("WARN", "#FFB300"),      // 警示金黄
    ERROR("ERROR", "#FF3366")     // 异常亮红
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

object TuiLogBus {
    private val listeners = mutableListOf<(LogEntry) -> Unit>()
    private val history = mutableListOf<LogEntry>()
    private const val MAX_HISTORY = 500

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun log(level: LogLevel, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            message = message
        )
        if (history.size >= MAX_HISTORY) {
            history.removeAt(0)
        }
        history.add(entry)
        listeners.forEach { it.invoke(entry) }
    }

    @Synchronized
    fun subscribe(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
        // 回放最近历史
        history.forEach { listener.invoke(it) }
    }

    @Synchronized
    fun unsubscribe(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun getAllLogText(): String {
        return history.joinToString(separator = "\n") { entry ->
            "[${entry.timestamp}] [${entry.level.name}] ${entry.message}"
        }
    }

    @Synchronized
    fun clear() {
        history.clear()
        val entry = LogEntry(timeFormat.format(Date()), LogLevel.INFO, "--- TUI 控制台日志已清空 ---")
        listeners.forEach { it.invoke(entry) }
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, "[$tag] $message")
    fun success(tag: String, message: String) = log(LogLevel.INFO, "[$tag] ✅ $message")
    fun warn(tag: String, message: String) = log(LogLevel.WARN, "[$tag] ⚠️ $message")
    fun error(tag: String, message: String) = log(LogLevel.ERROR, "[$tag] ❌ $message")

    fun logInfo(tag: String, message: String) = info(tag, message)
    fun logSuccess(tag: String, message: String) = success(tag, message)
    fun logWarn(tag: String, message: String) = warn(tag, message)
    fun logError(tag: String, message: String) = error(tag, message)
}
