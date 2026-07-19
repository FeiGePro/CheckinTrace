package io.github.feigepro.checkintrace

import android.content.Context
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
import io.github.feigepro.checkintrace.provider.CheckInRequestPacer
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoProvider
import io.github.feigepro.checkintrace.provider.skland.SklandProvider
import io.github.feigepro.checkintrace.security.CredentialRepository
import io.github.feigepro.checkintrace.security.EncryptedCredentialStore
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class AutoCheckInWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val taskId = DevLogger.newTaskId()
        val repository = CredentialRepository(EncryptedCredentialStore(applicationContext))
        val selected = applicationContext.getSharedPreferences("ui_settings", 0)
            .getStringSet("selected_games", null)?.toSet()
            ?: GameCatalog.builtIn.filter { it.enabledByDefault }.mapTo(mutableSetOf()) { it.id }
        val games = GameCatalog.builtIn.filter { it.id in selected }
        val providers = mapOf(
            ProviderType.MIHOYO to repository.loadMihoyo("default")?.let(::MihoyoProvider),
            ProviderType.SKLAND to repository.loadSklandToken("default")?.let(::SklandProvider),
        )
        DevLogger.info("自动任务", "每日签到开始，已选 ${games.size} 个游戏", taskId)
        val requestPacer = CheckInRequestPacer()

        for ((type, providerGames) in games.groupBy { it.provider }) {
            val provider = providers[type] ?: continue
            if (provider.validateCredential().isFailure) {
                DevLogger.warn("自动任务", "$type 登录验证失败，本次跳过", taskId)
                continue
            }
            var stopProvider = false
            for (game in providerGames) {
                if (stopProvider) break
                val roleResult = provider.getRoles(game)
                if (roleResult.isFailure) {
                    DevLogger.error("自动任务/${game.displayName}", roleResult.exceptionOrNull()?.message ?: "角色读取失败", taskId)
                    continue
                }
                val roles = roleResult.getOrThrow()
                for (role in roles) {
                    requestPacer.awaitTurn()
                    when (val result = provider.checkIn(game, role)) {
                        is CheckInResult.Success -> DevLogger.info("自动任务/${game.displayName}", result.message, taskId)
                        CheckInResult.AlreadyCheckedIn -> DevLogger.info("自动任务/${game.displayName}", "今日已签到", taskId)
                        is CheckInResult.Failure -> {
                            DevLogger.warn("自动任务/${game.displayName}", result.message, taskId)
                            if (result.code == "CAPTCHA_REQUIRED") {
                                stopProvider = true
                                break
                            }
                        }
                    }
                }
            }
        }
        DevLogger.info("自动任务", "每日签到结束", taskId)
        // 不让 WorkManager 自动重试，避免短时间内重复请求导致风控。
        return Result.success()
    }
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
        enqueue(context, currentTime(context), ExistingPeriodicWorkPolicy.KEEP)
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
