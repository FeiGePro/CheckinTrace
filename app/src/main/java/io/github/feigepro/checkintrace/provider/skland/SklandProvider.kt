package io.github.feigepro.checkintrace.provider.skland

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.GameRole
import io.github.feigepro.checkintrace.provider.CheckInProvider
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SklandProvider(
    private var credential: SklandCredentialBundle,
    private val api: SklandApi = SklandApi(),
    private val onCredentialUpdated: (SklandCredentialBundle) -> Unit = {},
) : CheckInProvider {
    private var session: SklandSession? = credential.toSessionOrNull()
    private var bindings: JsonObject? = null
    private var sessionPrepared = false
    private var signedRecoveryAttempted = false
    private var firstAttendanceRequest = true

    override suspend fun validateCredential(): Result<Unit> = prepareSession().map { Unit }

    override suspend fun getRoles(game: GameDefinition): Result<List<GameRole>> = runCatching {
        val response = bindings ?: executeWithSessionRecovery { api.getBindings(it) }.getOrThrow().also { bindings = it }
        val applications = response["data"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
        val app = applications.map { it.jsonObject }
            .firstOrNull { it.string("appCode") == game.appCode }
            ?: return@runCatching emptyList()
        val bindingItems = app["bindingList"]?.jsonArray ?: JsonArray(emptyList())
        when (game.appCode) {
            "arknights" -> bindingItems.mapNotNull { parseArknightsRole(game.id, it.jsonObject) }
            "endfield" -> bindingItems.flatMap { parseEndfieldRoles(game.id, it.jsonObject) }
            else -> emptyList()
        }
    }

    override suspend fun checkIn(game: GameDefinition, role: GameRole): CheckInResult {
        when (game.appCode) {
            "arknights" -> if (role.extra["channelMasterId"].isNullOrBlank()) {
                return CheckInResult.Failure("ROLE_INVALID", "角色缺少 channelMasterId")
            }
            "endfield" -> {
                if (role.extra["roleId"].isNullOrBlank()) {
                    return CheckInResult.Failure("ROLE_INVALID", "终末地角色缺少 roleId")
                }
                if (role.extra["serverId"].isNullOrBlank()) {
                    return CheckInResult.Failure("ROLE_INVALID", "终末地角色缺少 serverId")
                }
            }
            else -> return CheckInResult.Failure("GAME_UNSUPPORTED", "暂不支持 ${game.displayName}")
        }

        suspend fun request(currentSession: SklandSession): Result<JsonObject> = when (game.appCode) {
            "arknights" -> api.checkInArknights(
                uid = role.uid,
                channelMasterId = requireNotNull(role.extra["channelMasterId"]),
                session = currentSession,
            )
            "endfield" -> api.checkInEndfield(
                roleId = requireNotNull(role.extra["roleId"]),
                serverId = requireNotNull(role.extra["serverId"]),
                session = currentSession,
            )
            else -> Result.failure(IllegalArgumentException("不支持的游戏"))
        }

        val initialSession = prepareSession().getOrElse {
            return authFailure(it)
        }
        if (firstAttendanceRequest) {
            // 角色读取与首个签到请求之间保留间隔，避免形成瞬时请求突发。
            delay(FIRST_ATTENDANCE_DELAY_MILLIS)
            firstAttendanceRequest = false
        }
        var response = request(initialSession)
        val needsRecovery = response.exceptionOrNull() is SklandAuthException ||
            response.getOrNull()?.let(SklandAttendanceResponse::isAuthRequired) == true
        if (needsRecovery) {
            val recovered = recoverAfterSignedAuthFailure().getOrElse {
                return authFailure(it)
            }
            response = request(recovered)
        }

        return response.fold(
            onSuccess = SklandAttendanceResponse::parse,
            onFailure = {
                when {
                    it is SklandForbiddenException -> CheckInResult.Failure(
                        code = "RISK_BLOCKED",
                        message = "${it.message}。已停止森空岛后续请求，请勿连续重试；稍后先在官方 App 正常访问后再试。",
                    )
                    SklandCheckInFailurePolicy.isAmbiguousAfterSubmit(it) -> CheckInResult.Unknown(
                        "签到请求已发送，但未能读取完整响应；平台可能已经完成签到，本次不自动重试",
                    )
                    else -> CheckInResult.Failure(
                        code = "NETWORK_OR_PROTOCOL",
                        message = it.message ?: "请求失败",
                        retryable = SklandCheckInFailurePolicy.isSafeToRetry(it),
                    )
                }
            },
        )
    }

    private suspend fun <T> executeWithSessionRecovery(block: suspend (SklandSession) -> Result<T>): Result<T> {
        val current = prepareSession().getOrElse { return Result.failure(it) }
        val first = block(current)
        if (first.exceptionOrNull() !is SklandAuthException) return first
        val recovered = recoverAfterSignedAuthFailure().getOrElse { return Result.failure(it) }
        return block(recovered)
    }

    /**
     * 每个 Provider 实例（一次手动/自动任务）只准备一次会话：
     * - 新 token 12 小时内直接复用；
     * - 旧 token 或升级前无时间戳数据先刷新 sign token；
     * - 只有明确的鉴权失效才使用保存的 access token 重建整套凭证。
     */
    private suspend fun prepareSession(): Result<SklandSession> {
        if (sessionPrepared) return ensureSession()
        val current = ensureSession().getOrElse { return Result.failure(it) }
        val updatedAt = credential.signTokenUpdatedAtEpochSeconds
        val age = updatedAt?.let { Instant.now().epochSecond - it }
        if (age != null && age in 0 until SIGN_TOKEN_REFRESH_AGE_SECONDS) {
            sessionPrepared = true
            return Result.success(current)
        }

        val currentCred = credential.cred
        if (currentCred.isNullOrBlank()) {
            return exchangeAccessToken().onSuccess { sessionPrepared = true }
        }
        val refreshed = api.refreshSignToken(currentCred)
        if (refreshed.isSuccess) {
            val updated = credential.copy(
                signToken = refreshed.getOrThrow(),
                signTokenUpdatedAtEpochSeconds = Instant.now().epochSecond,
            )
            updateCredential(updated)
            sessionPrepared = true
            delay(SESSION_SETTLE_DELAY_MILLIS)
            return Result.success(requireNotNull(session))
        }

        val error = refreshed.exceptionOrNull() ?: IllegalStateException("刷新 sign token 失败")
        if (error is SklandForbiddenException) return Result.failure(error)
        if (error is SklandAuthException) {
            return exchangeAccessToken().onSuccess {
                sessionPrepared = true
                delay(SESSION_SETTLE_DELAY_MILLIS)
            }
        }
        return Result.failure(error)
    }

    /** 签名请求仍鉴权失败时，只允许用 access token 完整重建一次。 */
    private suspend fun recoverAfterSignedAuthFailure(): Result<SklandSession> {
        bindings = null
        if (signedRecoveryAttempted) {
            return Result.failure(IllegalStateException("森空岛会话恢复已尝试，请重新扫码登录"))
        }
        signedRecoveryAttempted = true
        return exchangeAccessToken().onSuccess {
            sessionPrepared = true
            delay(SESSION_SETTLE_DELAY_MILLIS)
        }
    }

    private suspend fun ensureSession(): Result<SklandSession> {
        session?.let { return Result.success(it) }
        return exchangeAccessToken()
    }

    private suspend fun exchangeAccessToken(): Result<SklandSession> {
        if (credential.accessToken.isBlank()) return Result.failure(IllegalStateException("森空岛 access token 为空"))
        return api.exchangeToken(credential.accessToken).map { updated ->
            updateCredential(updated)
            requireNotNull(session)
        }
    }

    private fun updateCredential(updated: SklandCredentialBundle) {
        credential = updated
        session = updated.toSessionOrNull()
        bindings = null
        onCredentialUpdated(updated)
    }

    private fun authFailure(error: Throwable): CheckInResult.Failure = when (error) {
        is SklandForbiddenException -> CheckInResult.Failure(
            code = "RISK_BLOCKED",
            message = "${error.message}。已停止森空岛后续请求，请勿连续重试。",
        )
        else -> CheckInResult.Failure(
            code = "AUTH_REQUIRED",
            message = error.message ?: "森空岛凭证已失效，请重新扫码登录",
        )
    }

    private fun parseArknightsRole(gameId: String, binding: JsonObject): GameRole? {
        val uid = binding.string("uid") ?: return null
        return GameRole(
            gameId = gameId,
            uid = uid,
            nickname = binding.string("nickName") ?: "未知角色",
            channelName = binding.string("channelName"),
            extra = mapOf("channelMasterId" to (binding.string("channelMasterId") ?: return null)),
        )
    }

    private fun parseEndfieldRoles(gameId: String, binding: JsonObject): List<GameRole> {
        val uid = binding.string("uid") ?: return emptyList()
        val roles = (binding["roles"] as? JsonArray)?.map { it.jsonObject }.orEmpty().ifEmpty {
            listOfNotNull(binding["defaultRole"] as? JsonObject)
        }
        return roles.mapNotNull { role ->
            GameRole(
                gameId = gameId,
                uid = uid,
                nickname = role.string("nickname") ?: "未知角色",
                channelName = role.string("serverName") ?: binding.string("channelName"),
                extra = mapOf(
                    "roleId" to (role.string("roleId") ?: return@mapNotNull null),
                    "serverId" to (role.string("serverId") ?: return@mapNotNull null),
                ),
            )
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private companion object {
        const val SIGN_TOKEN_REFRESH_AGE_SECONDS = 12 * 60 * 60L
        const val SESSION_SETTLE_DELAY_MILLIS = 800L
        const val FIRST_ATTENDANCE_DELAY_MILLIS = 2_000L
    }
}

private fun SklandCredentialBundle.toSessionOrNull(): SklandSession? {
    val storedCred = cred?.takeIf(String::isNotBlank) ?: return null
    val storedToken = signToken?.takeIf(String::isNotBlank) ?: return null
    return SklandSession(storedCred, storedToken)
}

internal object SklandAttendanceResponse {
    fun parse(response: JsonObject): CheckInResult {
        val code = response.int("code") ?: response.int("status")
        val message = response.string("message") ?: response.string("msg") ?: "未知响应"
        if (code == 0) return CheckInResult.Success("签到成功")
        if (
            code == 10001 ||
            message.contains("重复签到") ||
            message.contains("已签到") ||
            message.contains("请勿重复")
        ) {
            return CheckInResult.AlreadyCheckedIn
        }
        if (code == 10000 || code == 10002) {
            return CheckInResult.Failure("AUTH_REQUIRED", message)
        }
        return CheckInResult.Failure(
            code = code?.let { "API_$it" } ?: "PROTOCOL_ERROR",
            message = message,
        )
    }

    fun isAuthRequired(response: JsonObject): Boolean {
        val code = response.int("code") ?: response.int("status")
        return code == 10000 || code == 10002
    }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.content?.toIntOrNull()
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
}

internal object SklandCheckInFailurePolicy {
    fun isSafeToRetry(error: Throwable): Boolean = when (error) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException -> true
        else -> false
    }

    fun isAmbiguousAfterSubmit(error: Throwable): Boolean = when (error) {
        is SocketTimeoutException,
        is EOFException,
        is ProtocolException -> true
        else -> false
    }
}
