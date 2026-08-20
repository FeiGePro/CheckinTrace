package io.github.feigepro.checkintrace

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.ProviderType
import io.github.feigepro.checkintrace.logging.DevLogger
import io.github.feigepro.checkintrace.logging.LogRedactor
import io.github.feigepro.checkintrace.provider.CheckInProvider
import io.github.feigepro.checkintrace.provider.CheckInRequestPacer
import io.github.feigepro.checkintrace.provider.ProviderFailureException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Prevents a manual run and the scheduled worker from overlapping in-process. */
internal object CheckInExecutionLock {
    val mutex = Mutex()
}

internal suspend fun acquireCheckInExecutionLock(timeoutMillis: Long): Boolean =
    withTimeoutOrNull(timeoutMillis) {
        CheckInExecutionLock.mutex.lock()
        true
    } ?: false

/** Result of one platform pipeline; the two platform pipelines can be merged safely. */
internal data class ProviderCheckInOutcome(
    val lines: List<String> = emptyList(),
    val completedRoleKeys: Set<String> = emptySet(),
    val hasFailures: Boolean = false,
    val requiresAction: Boolean = false,
    val hasRetryableFailure: Boolean = false,
)

/**
 * Runs one provider serially. Each provider gets its own fixed pacer, so MiHoYo
 * and Skland may run at the same time while requests within one provider stay
 * low-frequency and deterministic.
 */
internal suspend fun executeProviderCheckIns(
    type: ProviderType,
    providerGames: List<GameDefinition>,
    provider: CheckInProvider?,
    previouslyCompletedRoleKeys: Set<String>,
    skipPreviouslyCompleted: Boolean,
    taskId: String?,
    minimumIntervalMillis: Long = CheckInRequestPacer.DEFAULT_INTERVAL_MILLIS,
    onRoleCompleted: suspend (roleKey: String, line: String) -> Unit = { _, _ -> },
): ProviderCheckInOutcome {
    val lines = mutableListOf<String>()
    val completedRoleKeys = previouslyCompletedRoleKeys.toMutableSet()
    var hasFailures = false
    var requiresAction = false
    var hasRetryableFailure = false

    if (provider == null) {
        lines += "${providerName(type)}：尚未登录"
        return ProviderCheckInOutcome(
            lines = lines,
            completedRoleKeys = completedRoleKeys,
            hasFailures = true,
            requiresAction = true,
        )
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
        return ProviderCheckInOutcome(lines, completedRoleKeys, hasFailures, requiresAction, hasRetryableFailure)
    }

    val requestPacer = CheckInRequestPacer(minimumIntervalMillis)
    var stopProvider = false
    for (game in providerGames) {
        if (stopProvider) break

        // Keep the role lookup for the next game behind the same provider-level
        // pacing as the attendance call. This avoids bursty per-game requests.
        requestPacer.awaitTurn()
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
            // A role-query failure is provider-wide (auth, risk, or a broken
            // protocol), so do not probe the same platform's next game.
            stopProvider = true
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
            if (skipPreviouslyCompleted && roleKey in previouslyCompletedRoleKeys) {
                lines += "$label：已在上次尝试完成，本次跳过"
                continue
            }

            requestPacer.awaitTurn()
            when (val result = provider.checkIn(game, role)) {
                is CheckInResult.Success -> {
                    completedRoleKeys += roleKey
                    val line = "$label：${safeMessage(result.message)}"
                    lines += line
                    DevLogger.info("自动任务/${game.displayName}", result.message, taskId)
                    onRoleCompleted(roleKey, line)
                }

                CheckInResult.AlreadyCheckedIn -> {
                    completedRoleKeys += roleKey
                    val line = "$label：今日已签到"
                    lines += line
                    DevLogger.info("自动任务/${game.displayName}", "今日已签到", taskId)
                    onRoleCompleted(roleKey, line)
                }

                is CheckInResult.Failure -> {
                    val message = safeMessage(result.message)
                    lines += "$label：失败（$message）"
                    DevLogger.warn("自动任务/${game.displayName}", message, taskId)
                    hasFailures = true
                    if (result.retryable) hasRetryableFailure = true
                    if (result.code in ACTION_REQUIRED_CODES) requiresAction = true

                    if (result.code in ACTION_REQUIRED_CODES || result.retryable ||
                        result.code == "NETWORK_OR_PROTOCOL" || result.code == "PROTOCOL_ERROR"
                    ) {
                        if (result.code in ACTION_REQUIRED_CODES) {
                            lines += "检测到${actionDescription(result.code)}，已停止${providerName(type)}后续请求"
                        } else {
                            lines += "检测到请求异常，已停止${providerName(type)}本轮后续请求"
                        }
                        stopProvider = true
                        break
                    }
                }
            }
        }
    }

    return ProviderCheckInOutcome(
        lines = lines,
        completedRoleKeys = completedRoleKeys,
        hasFailures = hasFailures,
        requiresAction = requiresAction,
        hasRetryableFailure = hasRetryableFailure,
    )
}

/**
 * Converts an unexpected provider/parser exception into an isolated provider
 * outcome. One malformed response must not cancel the other platform's task.
 */
internal suspend fun executeProviderCheckInsSafely(
    type: ProviderType,
    providerGames: List<GameDefinition>,
    provider: CheckInProvider?,
    previouslyCompletedRoleKeys: Set<String>,
    skipPreviouslyCompleted: Boolean,
    taskId: String?,
    minimumIntervalMillis: Long = CheckInRequestPacer.DEFAULT_INTERVAL_MILLIS,
    onRoleCompleted: suspend (roleKey: String, line: String) -> Unit = { _, _ -> },
): ProviderCheckInOutcome = try {
    executeProviderCheckIns(
        type = type,
        providerGames = providerGames,
        provider = provider,
        previouslyCompletedRoleKeys = previouslyCompletedRoleKeys,
        skipPreviouslyCompleted = skipPreviouslyCompleted,
        taskId = taskId,
        minimumIntervalMillis = minimumIntervalMillis,
        onRoleCompleted = onRoleCompleted,
    )
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    val message = safeMessage(error.message ?: error.javaClass.simpleName)
    DevLogger.error("自动任务/${providerName(type)}", message, taskId)
    ProviderCheckInOutcome(
        lines = listOf("${providerName(type)}：未处理异常（$message）"),
        completedRoleKeys = previouslyCompletedRoleKeys,
        hasFailures = true,
        requiresAction = true,
    )
}

internal fun isTransientError(error: Throwable?): Boolean {
    if (error is ProviderFailureException) return error.retryable
    return when (error) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        is SocketTimeoutException,
        -> true

        else -> false
    }
}

private fun providerName(type: ProviderType): String =
    if (type == ProviderType.MIHOYO) "米游社" else "森空岛"

private fun actionDescription(code: String): String = when (code) {
    "CAPTCHA_REQUIRED" -> "人工验证要求"
    "AUTH_REQUIRED" -> "重新登录要求"
    "FIRST_BIND_REQUIRED" -> "首次绑定要求"
    else -> "平台操作要求"
}

private fun safeMessage(message: String): String = LogRedactor.redact(message)

private val ACTION_REQUIRED_CODES = setOf("CAPTCHA_REQUIRED", "AUTH_REQUIRED", "FIRST_BIND_REQUIRED")
