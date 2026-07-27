package io.github.feigepro.checkintrace

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameCatalog
import io.github.feigepro.checkintrace.data.ProviderType
import io.github.feigepro.checkintrace.logging.DevLogger
import io.github.feigepro.checkintrace.logging.LogRedactor
import io.github.feigepro.checkintrace.provider.CheckInRequestPacer
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoProvider
import io.github.feigepro.checkintrace.provider.skland.SklandProvider
import io.github.feigepro.checkintrace.security.CredentialRepository
import io.github.feigepro.checkintrace.security.EncryptedCredentialStore
import java.io.IOException
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class AutoCheckInWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val taskId = DevLogger.newTaskId()
        val statusStore = AutoCheckInStatusStore(applicationContext)
        val previousSnapshot = statusStore.load()
        val startedAt = System.currentTimeMillis()
        val attempt = runAttemptCount + 1
        val completedRoleKeys = if (
            runAttemptCount > 0 && previousSnapshot?.state == AutoCheckInRunState.RETRY_SCHEDULED
        ) {
            previousSnapshot.completedRoleKeys.toMutableSet()
        } else {
            mutableSetOf()
        }
        statusStore.save(
            AutoCheckInSnapshot(
                state = AutoCheckInRunState.RUNNING,
                startedAtEpochMillis = startedAt,
                attempt = attempt,
                lines = listOf("自动签到正在执行"),
                completedRoleKeys = completedRoleKeys,
            ),
        )

        val lines = mutableListOf<String>()
        return try {
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
            val requestPacer = CheckInRequestPacer()

            DevLogger.info("自动任务", "每日签到开始，已选 ${games.size} 个游戏，第 $attempt 次尝试", taskId)
            if (games.isEmpty()) lines += "没有选择需要自动签到的游戏"

            for ((type, providerGames) in games.groupBy { it.provider }) {
                val provider = providers[type]
                if (provider == null) {
                    lines += "${providerName(type)}：尚未登录"
                    hasFailures = true
                    requiresAction = true
                    continue
                }

                val validation = provider.validateCredential()
                if (validation.isFailure) {
                    val error = validation.exceptionOrNull()
                    val message = safeMessage(error?.message ?: "登录验证失败")
                    lines += "${providerName(type)}：登录验证失败（$message）"
                    DevLogger.warn("自动任务", "${providerName(type)} 登录验证失败，本次跳过", taskId)
                    hasFailures = true
                    if (isTransientError(error)) {
                        hasRetryableFailure = true
                    } else {
                        requiresAction = true
                    }
                    continue
                }

                var stopProvider = false
                for (game in providerGames) {
                    if (stopProvider) break
                    val roleResult = provider.getRoles(game)
                    if (roleResult.isFailure) {
                        val error = roleResult.exceptionOrNull()
                        val message = safeMessage(error?.message ?: "角色读取失败")
                        lines += "${game.displayName}：读取角色失败（$message）"
                        DevLogger.error("自动任务/${game.displayName}", message, taskId)
                        hasFailures = true
                        if (isTransientError(error)) {
                            hasRetryableFailure = true
                        } else {
                            requiresAction = true
                        }
                        continue
                    }

                    val roles = roleResult.getOrThrow()
                    if (roles.isEmpty()) {
                        lines += "${game.displayName}：没有找到绑定角色"
                        hasFailures = true
                        continue
                    }

                    for (role in roles) {
                        val label = "${game.displayName} · ${role.nickname}"
                        val roleKey = autoCheckInRoleKey(game.id, role.uid, role.extra)
                        if (runAttemptCount > 0 && roleKey in completedRoleKeys) {
                            lines += "$label：已在上次尝试完成，本次跳过"
                            continue
                        }

                        requestPacer.awaitTurn()
                        when (val result = provider.checkIn(game, role)) {
                            is CheckInResult.Success -> {
                                completedRoleKeys += roleKey
                                lines += "$label：${safeMessage(result.message)}"
                                DevLogger.info("自动任务/${game.displayName}", result.message, taskId)
                            }

                            CheckInResult.AlreadyCheckedIn -> {
                                completedRoleKeys += roleKey
                                lines += "$label：今日已签到"
                                DevLogger.info("自动任务/${game.displayName}", "今日已签到", taskId)
                            }

                            is CheckInResult.Failure -> {
                                val message = safeMessage(result.message)
                                lines += "$label：失败（$message）"
                                DevLogger.warn("自动任务/${game.displayName}", message, taskId)
                                hasFailures = true
                                if (result.retryable) hasRetryableFailure = true
                                if (result.code in ACTION_REQUIRED_CODES) requiresAction = true
                                if (result.code == "CAPTCHA_REQUIRED") {
                                    lines += "检测到人工验证要求，已停止${providerName(type)}后续请求"
                                    stopProvider = true
                                    break
                                }
                            }
                        }
                    }
                }
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
            if (!decision.shouldRetry && decision.state in NOTIFIABLE_FAILURE_STATES) {
                AutoCheckInNotifier.notifyFailure(applicationContext, snapshot)
            }
            DevLogger.info("自动任务", "每日签到结束，状态=${decision.state}", taskId)
            if (decision.shouldRetry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = safeMessage(error.message ?: error.javaClass.simpleName)
            lines += "自动任务异常（$message）"
            DevLogger.error("自动任务", message, taskId)
            val decision = decideAutoCheckInCompletion(
                hasFailures = true,
                requiresAction = false,
                hasRetryableFailure = true,
                runAttemptCount = runAttemptCount,
            )
            if (decision.shouldRetry) lines += "将在约 $RETRY_BACKOFF_HOURS 小时后自动重试一次；已完成角色不会重复请求"
            val snapshot = AutoCheckInSnapshot(
                state = decision.state,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = System.currentTimeMillis(),
                attempt = attempt,
                lines = lines,
                completedRoleKeys = completedRoleKeys,
            )
            runCatching { statusStore.save(snapshot) }
            if (!decision.shouldRetry) AutoCheckInNotifier.notifyFailure(applicationContext, snapshot)
            if (decision.shouldRetry) Result.retry() else Result.success()
        }
    }

    private fun safeMessage(message: String): String = LogRedactor.redact(message)

    private fun isTransientError(error: Throwable?): Boolean {
        if (error is IOException) return true
        val message = error?.message.orEmpty()
        return message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ||
            Regex("HTTP (429|5\\d\\d)").containsMatchIn(message)
    }

    private fun providerName(type: ProviderType): String =
        if (type == ProviderType.MIHOYO) "米游社" else "森空岛"

    private companion object {
        const val DEFAULT_ACCOUNT = "default"
        val ACTION_REQUIRED_CODES = setOf("CAPTCHA_REQUIRED", "AUTH_REQUIRED", "FIRST_BIND_REQUIRED")
        val NOTIFIABLE_FAILURE_STATES = setOf(AutoCheckInRunState.FAILED, AutoCheckInRunState.ACTION_REQUIRED)
    }
}

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

    fun ensureScheduled(context: Context) {
        enqueue(context, currentTime(context), ExistingPeriodicWorkPolicy.UPDATE)
    }

    fun updateTime(context: Context, hour: Int, minute: Int) {
        val time = DailyCheckInTime(hour, minute)
        context.getSharedPreferences(SCHEDULER_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(HOUR_KEY, time.hour)
            .putInt(MINUTE_KEY, time.minute)
            .apply()
        enqueue(context, time, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
    }

    internal fun nextRun(now: ZonedDateTime, time: DailyCheckInTime): ZonedDateTime {
        var next = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }

    private fun enqueue(
        context: Context,
        time: DailyCheckInTime,
        policy: ExistingPeriodicWorkPolicy,
    ) {
        val now = ZonedDateTime.now()
        val request = PeriodicWorkRequestBuilder<AutoCheckInWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, nextRun(now, time)))
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        DevLogger.info("自动任务", "低功耗任务已安排，每天约 ${time.label} 执行")
    }

    private const val WORK_NAME = "daily-game-check-in"
    private const val SCHEDULER_PREFERENCES = "scheduler_settings"
    private const val HOUR_KEY = "schedule_hour"
    private const val MINUTE_KEY = "schedule_minute"
    private const val DEFAULT_HOUR = 8
    private const val DEFAULT_MINUTE = 30
}

private const val RETRY_BACKOFF_HOURS = 1L
