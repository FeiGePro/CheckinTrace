package io.github.feigepro.checkintrace

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.feigepro.checkintrace.data.GameCatalog
import io.github.feigepro.checkintrace.data.ProviderType
import io.github.feigepro.checkintrace.logging.DevLogger
import io.github.feigepro.checkintrace.logging.LogRedactor
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoProvider
import io.github.feigepro.checkintrace.provider.skland.SklandProvider
import io.github.feigepro.checkintrace.security.CredentialRepository
import io.github.feigepro.checkintrace.security.EncryptedCredentialStore
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AutoCheckInWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        DevLogger.initialize(applicationContext)
        val taskId = DevLogger.newTaskId()
        val statusStore = AutoCheckInStatusStore(applicationContext)
        val previousSnapshot = statusStore.load()
        val startedAt = System.currentTimeMillis()
        val attempt = runAttemptCount + 1
        if (!acquireCheckInExecutionLock(AUTO_LOCK_WAIT_MILLIS)) {
            AutoCheckInScheduler.enqueueShortRetry(applicationContext)
            DevLogger.info("自动任务", "已有签到任务正在执行，已安排约 ${AUTO_LOCK_RETRY_DELAY_MILLIS / 1000} 秒后重试", taskId)
            return Result.success()
        }
        val completedRoleKeys = if (
            runAttemptCount > 0 && previousSnapshot?.state?.let { it in RESUMABLE_STATES } == true
        ) {
            previousSnapshot.completedRoleKeys.toMutableSet()
        } else {
            mutableSetOf()
        }

        val lines = mutableListOf<String>()
        val checkpointLines = mutableListOf<String>()
        val checkpointMutex = Mutex()
        var terminalSnapshotSaved = false
        return try {
            // Keep the first snapshot inside the protected try/finally. A disk
            // failure here must never strand the process-local execution lock.
            statusStore.save(
                AutoCheckInSnapshot(
                    state = AutoCheckInRunState.RUNNING,
                    startedAtEpochMillis = startedAt,
                    attempt = attempt,
                    lines = listOf("自动签到正在执行"),
                    completedRoleKeys = completedRoleKeys,
                ),
            )

            val repository = CredentialRepository(EncryptedCredentialStore(applicationContext))
            val selected = applicationContext.getSharedPreferences("ui_settings", 0)
                .getStringSet("selected_games", null)?.toSet()
                ?: GameCatalog.builtIn.filter { it.enabledByDefault }.mapTo(mutableSetOf()) { it.id }
            val games = GameCatalog.builtIn.filter { it.id in selected }
            val providers = mapOf(
                ProviderType.MIHOYO to repository.loadMihoyo(DEFAULT_ACCOUNT)?.let(::MihoyoProvider),
                ProviderType.SKLAND to repository.loadSklandToken(DEFAULT_ACCOUNT)?.let(::SklandProvider),
            )
            var hasFailures = false
            var requiresAction = false
            var hasRetryableFailure = false

            DevLogger.info("自动任务", "每日签到开始，已选 ${games.size} 个游戏，第 $attempt 次尝试", taskId)
            if (games.isEmpty()) lines += "没有选择需要自动签到的游戏"

            val outcomes = supervisorScope {
                games.groupBy { it.provider }.map { (type, providerGames) ->
                    async {
                        executeProviderCheckInsSafely(
                            type = type,
                            providerGames = providerGames,
                            provider = providers[type],
                            previouslyCompletedRoleKeys = completedRoleKeys,
                            skipPreviouslyCompleted = runAttemptCount > 0,
                            taskId = taskId,
                            onRoleCompleted = { roleKey, line ->
                                checkpointMutex.withLock {
                                    completedRoleKeys += roleKey
                                    checkpointLines += line
                                    statusStore.save(
                                        AutoCheckInSnapshot(
                                            state = AutoCheckInRunState.RUNNING,
                                            startedAtEpochMillis = startedAt,
                                            attempt = attempt,
                                            lines = listOf("自动签到正在执行") + checkpointLines.takeLast(MAX_SNAPSHOT_LINES),
                                            completedRoleKeys = completedRoleKeys,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }.awaitAll()
            }
            outcomes.forEach { outcome ->
                lines += outcome.lines
                completedRoleKeys += outcome.completedRoleKeys
                hasFailures = hasFailures || outcome.hasFailures
                requiresAction = requiresAction || outcome.requiresAction
                hasRetryableFailure = hasRetryableFailure || outcome.hasRetryableFailure
            }

            val decision = decideAutoCheckInCompletion(
                hasFailures = hasFailures,
                requiresAction = requiresAction,
                hasRetryableFailure = hasRetryableFailure,
                runAttemptCount = runAttemptCount,
            )
            if (decision.shouldRetry) {
                lines += "检测到临时错误，将在约 $RETRY_BACKOFF_HOURS 小时后自动重试一次；已完成角色不会重复请求"
            }
            val snapshot = AutoCheckInSnapshot(
                state = decision.state,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = System.currentTimeMillis(),
                attempt = attempt,
                lines = lines.ifEmpty { listOf("自动签到已完成") },
                completedRoleKeys = completedRoleKeys,
            )
            statusStore.save(snapshot)
            terminalSnapshotSaved = true
            if (!decision.shouldRetry && decision.state in NOTIFIABLE_FAILURE_STATES) {
                AutoCheckInNotifier.notifyFailure(applicationContext, snapshot)
            }
            DevLogger.info("自动任务", "每日签到结束，状态=${decision.state}", taskId)
            if (decision.shouldRetry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                terminalSnapshotSaved = runCatching {
                    statusStore.save(
                        AutoCheckInSnapshot(
                            state = AutoCheckInRunState.INTERRUPTED,
                            startedAtEpochMillis = startedAt,
                            finishedAtEpochMillis = System.currentTimeMillis(),
                            attempt = attempt,
                            lines = lines + "自动任务被系统或用户中断，已保留已完成角色",
                            completedRoleKeys = completedRoleKeys,
                        ),
                    )
                }.onFailure { DevLogger.error("自动任务", "保存中断状态失败：${it.message}", taskId) }.isSuccess
            }
            throw cancelled
        } catch (error: Exception) {
            val message = safeMessage(error.message ?: error.javaClass.simpleName)
            lines += "自动任务异常（$message）"
            DevLogger.error("自动任务", message, taskId)
            val decision = decideAutoCheckInCompletion(
                hasFailures = true,
                requiresAction = false,
                // An uncaught application/protocol exception is not evidence
                // that repeating a POST is safe. Provider code already marks
                // explicitly retryable transport failures before reaching here.
                hasRetryableFailure = false,
                runAttemptCount = runAttemptCount,
            )
            val snapshot = AutoCheckInSnapshot(
                state = decision.state,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = System.currentTimeMillis(),
                attempt = attempt,
                lines = lines,
                completedRoleKeys = completedRoleKeys,
            )
            terminalSnapshotSaved = runCatching { statusStore.save(snapshot) }
                .onFailure { DevLogger.error("自动任务", "保存最终状态失败：${it.message}", taskId) }
                .isSuccess
            if (!decision.shouldRetry) AutoCheckInNotifier.notifyFailure(applicationContext, snapshot)
            if (decision.shouldRetry) Result.retry() else Result.success()
        } finally {
            if (!terminalSnapshotSaved) {
                runCatching {
                    statusStore.save(
                        AutoCheckInSnapshot(
                            state = AutoCheckInRunState.INTERRUPTED,
                            startedAtEpochMillis = startedAt,
                            finishedAtEpochMillis = System.currentTimeMillis(),
                            attempt = attempt,
                            lines = lines + "自动任务未正常结束",
                            completedRoleKeys = completedRoleKeys,
                        ),
                    )
                }
            }
            DevLogger.flush()
            CheckInExecutionLock.mutex.unlock()
        }
    }

    private fun safeMessage(message: String): String = LogRedactor.redact(message)

    private companion object {
        const val DEFAULT_ACCOUNT = "default"
        const val AUTO_LOCK_WAIT_MILLIS = 5 * 60 * 1000L
        const val MAX_SNAPSHOT_LINES = 100
        val NOTIFIABLE_FAILURE_STATES = setOf(AutoCheckInRunState.FAILED, AutoCheckInRunState.ACTION_REQUIRED)
        val RESUMABLE_STATES = setOf(AutoCheckInRunState.RETRY_SCHEDULED, AutoCheckInRunState.INTERRUPTED)
    }
}

internal const val AUTO_LOCK_RETRY_DELAY_MILLIS = 30_000L

internal fun autoCheckInRoleKey(gameId: String, uid: String, extra: Map<String, String>): String {
    val extraPart = extra.toSortedMap().entries.joinToString("&") { (key, value) -> "$key=$value" }
    return "$gameId|$uid|$extraPart"
}

data class DailyCheckInTime(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23)
        require(minute in 0..59)
    }

    val label: String get() = "%02d:%02d".format(hour, minute)
}

object AutoCheckInScheduler {
    fun currentTime(context: Context): DailyCheckInTime {
        val preferences = context.getSharedPreferences(SCHEDULER_PREFERENCES, Context.MODE_PRIVATE)
        return DailyCheckInTime(
            hour = preferences.getInt(HOUR_KEY, DEFAULT_HOUR),
            minute = preferences.getInt(MINUTE_KEY, DEFAULT_MINUTE),
        )
    }

    fun ensureScheduled(context: Context, forceReschedule: Boolean = false) {
        cancelLegacyPeriodicWork(context)
        if (!forceReschedule && hasPendingAlarm(context)) return
        if (forceReschedule) cancelAlarm(context)
        scheduleAlarm(context, currentTime(context), ZonedDateTime.now())
    }

    fun updateTime(context: Context, hour: Int, minute: Int) {
        val time = DailyCheckInTime(hour, minute)
        context.getSharedPreferences(SCHEDULER_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(HOUR_KEY, time.hour)
            .putInt(MINUTE_KEY, time.minute)
            .commit()
        cancelLegacyPeriodicWork(context)
        cancelAlarm(context)
        scheduleAlarm(context, time, ZonedDateTime.now())
    }

    fun scheduleNextAlarm(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        scheduleAlarm(context, currentTime(context), now)
    }

    fun enqueueWork(context: Context) {
        cancelLegacyPeriodicWork(context)
        val request = OneTimeWorkRequestBuilder<AutoCheckInWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueShortRetry(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoCheckInRetryWorker>()
            .setInitialDelay(AUTO_LOCK_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(LOCK_RETRY_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            LOCK_RETRY_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    internal fun nextRun(now: ZonedDateTime, time: DailyCheckInTime): ZonedDateTime {
        var next = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }

    private fun scheduleAlarm(
        context: Context,
        time: DailyCheckInTime,
        now: ZonedDateTime,
    ) {
        val target = nextRun(now, time)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = alarmPendingIntent(context)
        val exactAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        val exact = if (exactAvailable) {
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    target.toInstant().toEpochMilli(),
                    pendingIntent,
                )
            }.isSuccess
        } else {
            false
        }
        if (!exact) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                target.toInstant().toEpochMilli(),
                pendingIntent,
            )
        }
        val mode = if (exact) "精确定时" else "省电近似定时（未授予精确闹钟权限）"
        DevLogger.info("自动任务", "系统任务已安排，目标 ${target.toLocalDateTime()}，$mode")
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmPendingIntent(context))
    }

    private fun hasPendingAlarm(context: Context): Boolean = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, AutoCheckInAlarmReceiver::class.java).setAction(ALARM_ACTION),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) != null

    private fun cancelLegacyPeriodicWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, AutoCheckInAlarmReceiver::class.java).setAction(ALARM_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    const val ALARM_ACTION = "io.github.feigepro.checkintrace.action.DAILY_CHECK_IN"

    private const val WORK_NAME = "daily-game-check-in-alarm-run"
    private const val LOCK_RETRY_WORK_NAME = "daily-game-check-in-lock-retry"
    private const val LEGACY_PERIODIC_WORK_NAME = "daily-game-check-in"
    private const val ALARM_REQUEST_CODE = 1208
    private const val SCHEDULER_PREFERENCES = "scheduler_settings"
    private const val HOUR_KEY = "schedule_hour"
    private const val MINUTE_KEY = "schedule_minute"
    private const val DEFAULT_HOUR = 8
    private const val DEFAULT_MINUTE = 30
}

private const val RETRY_BACKOFF_HOURS = 1L
