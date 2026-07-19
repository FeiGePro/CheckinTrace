package io.github.feigepro.checkintrace.logging

import android.util.Log
import io.github.feigepro.checkintrace.BuildConfig
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class DevLogLevel { DEBUG, INFO, WARN, ERROR }

data class DevLogEntry(
    val timestamp: Instant,
    val level: DevLogLevel,
    val scope: String,
    val message: String,
    val taskId: String?,
)

object DevLogger {
    private const val TAG = "CheckinTrace"
    private const val MAX_ENTRIES = 300
    private val entries = CopyOnWriteArrayList<DevLogEntry>()

    fun newTaskId(): String = UUID.randomUUID().toString().take(8)

    fun snapshot(): List<DevLogEntry> = entries.toList()

    fun clear() = entries.clear()

    fun debug(scope: String, message: String, taskId: String? = null) {
        if (BuildConfig.DEBUG) append(DevLogLevel.DEBUG, scope, message, taskId)
    }

    fun info(scope: String, message: String, taskId: String? = null) =
        append(DevLogLevel.INFO, scope, message, taskId)

    fun warn(scope: String, message: String, taskId: String? = null) =
        append(DevLogLevel.WARN, scope, message, taskId)

    fun error(scope: String, message: String, taskId: String? = null) =
        append(DevLogLevel.ERROR, scope, message, taskId)

    private fun append(level: DevLogLevel, scope: String, rawMessage: String, taskId: String?) {
        if (!BuildConfig.DEBUG) return
        val message = LogRedactor.redact(rawMessage)
        val entry = DevLogEntry(Instant.now(), level, scope, message, taskId)
        entries += entry
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)

        val line = buildString {
            if (taskId != null) append("[$taskId] ")
            append("[$scope] $message")
        }
        when (level) {
            DevLogLevel.DEBUG -> Log.d(TAG, line)
            DevLogLevel.INFO -> Log.i(TAG, line)
            DevLogLevel.WARN -> Log.w(TAG, line)
            DevLogLevel.ERROR -> Log.e(TAG, line)
        }
    }
}

internal object LogRedactor {
    private val secretPatterns = listOf(
        Regex("(?i)([\"']?(cookie|authorization|stoken|cookie_token|sign_token|cred|token)[\"']?\\s*[:=]\\s*)[\"']?[^\"',;\\s}]+[\"']?"),
        Regex("(?i)([\"']?(account_id|account_mid|device_fp|device_id)[\"']?\\s*[:=]\\s*)[\"']?[^\"',;\\s}]+[\"']?"),
    )

    fun redact(input: String): String = secretPatterns.fold(input) { value, pattern ->
        pattern.replace(value) { match -> "${match.groupValues[1]}[REDACTED]" }
    }
}
