package io.github.feigepro.checkintrace.logging

import android.content.Context
import android.util.Log
import io.github.feigepro.checkintrace.BuildConfig
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

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
    private const val FILE_NAME = "checkintrace-dev.log"
    private val entries = CopyOnWriteArrayList<DevLogEntry>()
    private val storageLock = Any()
    private val storageExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "checkintrace-log").apply { isDaemon = true }
    }
    private var storageContext: Context? = null
    private var storageLoaded = false
    private var persistRunning = false
    private var persistDirty = false

    fun newTaskId(): String = UUID.randomUUID().toString().take(8)

    /** Loads a capped, redacted debug log ring buffer after a process restart. */
    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(storageLock) {
            if (storageLoaded) return
            storageContext = context.applicationContext
            runCatching<Unit> {
                storageContext?.openFileInput(FILE_NAME)?.bufferedReader()?.useLines { lines ->
                    val loaded = lines.mapNotNull { line -> decode(line) }.toList()
                    entries.addAll(loaded.takeLast(MAX_ENTRIES))
                }
            }.onFailure { Log.w(TAG, "读取开发日志失败", it) }
            storageLoaded = true
        }
    }

    fun snapshot(): List<DevLogEntry> = entries.toList()

    fun clear() {
        synchronized(storageLock) {
            entries.clear()
            requestPersistLocked()
        }
    }

    /** Flushes pending entries at a task boundary before a worker can finish. */
    fun flush() {
        if (!BuildConfig.DEBUG) return
        synchronized(storageLock) {
            persistDirty = false
            persistLocked()
        }
    }

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
        synchronized(storageLock) {
            entries += entry
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            requestPersistLocked()
        }

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

    private fun persistLocked() {
        val context = storageContext ?: return
        runCatching {
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).bufferedWriter().use { writer ->
                entries.forEach { entry ->
                    writer.append(encode(entry))
                    writer.newLine()
                }
            }
        }.onFailure { Log.w(TAG, "写入开发日志失败", it) }
    }

    /** Coalesces writes so logging never blocks the UI or a provider request. */
    private fun requestPersistLocked() {
        persistDirty = true
        if (persistRunning) return
        persistRunning = true
        storageExecutor.execute {
            while (true) {
                synchronized(storageLock) {
                    if (!persistDirty) {
                        persistRunning = false
                        return@execute
                    }
                    persistDirty = false
                    persistLocked()
                }
            }
        }
    }

    private fun encode(entry: DevLogEntry): String = listOf(
        entry.timestamp.toString(),
        entry.level.name,
        entry.taskId.orEmpty(),
        entry.scope,
        entry.message,
    ).joinToString("\t") { it.replace("\t", " ").replace("\r", " ").replace("\n", " ") }

    private fun decode(line: String): DevLogEntry? = runCatching {
        val parts = line.split('\t', limit = 5)
        require(parts.size == 5)
        DevLogEntry(
            timestamp = Instant.parse(parts[0]),
            level = DevLogLevel.valueOf(parts[1]),
            taskId = parts[2].ifBlank { null },
            scope = parts[3],
            message = parts[4],
        )
    }.getOrNull()
}

internal object LogRedactor {
    private val secretPatterns = listOf(
        Regex("(?i)(\\bAuthorization\\s*:\\s*Bearer\\s+)[^\\s,;]+"),
        Regex("(?i)([\"']?(cookie|authorization|stoken|cookie_token|sign_token|cred|token)[\"']?\\s*[:=]\\s*)[\"']?[^\"',;\\s}]+[\"']?"),
        Regex("(?i)([\"']?(account_id|account_mid|device_fp|device_id)[\"']?\\s*[:=]\\s*)[\"']?[^\"',;\\s}]+[\"']?"),
    )

    fun redact(input: String): String = secretPatterns.fold(input) { value, pattern ->
        pattern.replace(value) { match -> "${match.groupValues[1]}[REDACTED]" }
    }
}
