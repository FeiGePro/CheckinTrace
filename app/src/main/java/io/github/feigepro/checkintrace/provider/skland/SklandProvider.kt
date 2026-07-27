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

    override suspend fun validateCredential(): Result<Unit> = ensureSession().map { Unit }

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
        val request: suspend (SklandSession) -> Result<JsonObject> = when (game.appCode) {
            "arknights" -> {
                val channelMasterId = role.extra["channelMasterId"]
                    ?: return CheckInResult.Failure("ROLE_INVALID", "角色缺少 channelMasterId")
                suspend fun(currentSession: SklandSession): Result<JsonObject> =
                    api.checkInArknights(role.uid, channelMasterId, currentSession)
            }
            "endfield" -> {
                val roleId = role.extra["roleId"]
                    ?: return CheckInResult.Failure("ROLE_INVALID", "终末地角色缺少 roleId")
                val serverId = role.extra["serverId"]
                    ?: return CheckInResult.Failure("ROLE_INVALID", "终末地角色缺少 serverId")
                suspend fun(currentSession: SklandSession): Result<JsonObject> =
                    api.checkInEndfield(roleId, serverId, currentSession)
            }
            else -> return CheckInResult.Failure("GAME_UNSUPPORTED", "暂不支持 ${game.displayName}")
        }

        val initialSession = ensureSession().getOrElse {
            return CheckInResult.Failure("AUTH_REQUIRED", it.message ?: "请先完成森空岛登录")
        }
        var response = request(initialSession)
        val needsRecovery = response.exceptionOrNull() is SklandAuthException ||
            response.getOrNull()?.let(SklandAttendanceResponse::isAuthRequired) == true
        if (needsRecovery) {
            val recovered = recoverSession().getOrElse {
                return CheckInResult.Failure("AUTH_REQUIRED", it.message ?: "森空岛凭证已失效")
            }
            response = request(recovered)
        }

        return response.fold(
            onSuccess = SklandAttendanceResponse::parse,
            onFailure = {
                if (SklandCheckInFailurePolicy.isAmbiguousAfterSubmit(it)) {
                    CheckInResult.Unknown("签到请求已发送，但未能读取完整响应；平台可能已经完成签到，本次不自动重试")
                } else {
                    CheckInResult.Failure(
                        code = "NETWORK_OR_PROTOCOL",
                        message = it.message ?: "请求失败",
                        retryable = SklandCheckInFailurePolicy.isSafeToRetry(it),
                    )
                }
            },
        )
    }

    private suspend fun <T> executeWithSessionRecovery(block: suspend (SklandSession) -> Result<T>): Result<T> {
        val current = ensureSession().getOrElse { return Result.failure(it) }
        val first = block(current)
        if (first.exceptionOrNull() !is SklandAuthException) return first
        val recovered = recoverSession().getOrElse { return Result.failure(it) }
        return block(recovered)
    }

    private suspend fun ensureSession(): Result<SklandSession> {
        session?.let { return Result.success(it) }
        return exchangeAccessToken()
    }

    private suspend fun recoverSession(): Result<SklandSession> {
        bindings = null
        val currentCred = credential.cred
        if (!currentCred.isNullOrBlank()) {
            val refreshed = api.refreshSignToken(currentCred)
            if (refreshed.isSuccess) {
                val updated = credential.copy(signToken = refreshed.getOrThrow())
                updateCredential(updated)
                return Result.success(requireNotNull(session))
            }
        }
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
