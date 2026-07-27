package io.github.feigepro.checkintrace.provider.skland

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.GameRole
import io.github.feigepro.checkintrace.provider.CheckInProvider
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SklandProvider(
    private val loginToken: String,
    private val api: SklandApi = SklandApi(),
) : CheckInProvider {
    private var session: SklandSession? = null
    private var bindings: JsonObject? = null

    override suspend fun validateCredential(): Result<Unit> =
        api.exchangeToken(loginToken).map {
            session = it
            bindings = null
        }

    override suspend fun getRoles(game: GameDefinition): Result<List<GameRole>> = runCatching {
        // 明日方舟与终末地共用同一份绑定列表；一轮任务只请求一次。
        val response = bindings ?: api.getBindings(requireSession()).getOrThrow().also { bindings = it }
        val applications = response["data"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
        val app = applications.map { it.jsonObject }
            .firstOrNull { it.string("appCode") == game.appCode }
            ?: return@runCatching emptyList()
        val bindings = app["bindingList"]?.jsonArray ?: JsonArray(emptyList())
        when (game.appCode) {
            "arknights" -> bindings.mapNotNull { parseArknightsRole(game.id, it.jsonObject) }
            "endfield" -> bindings.flatMap { parseEndfieldRoles(game.id, it.jsonObject) }
            else -> emptyList()
        }
    }

    override suspend fun checkIn(game: GameDefinition, role: GameRole): CheckInResult {
        val currentSession = runCatching { requireSession() }.getOrElse {
            return CheckInResult.Failure("AUTH_REQUIRED", "请先完成森空岛登录")
        }
        val response = when (game.appCode) {
            "arknights" -> {
                val channelMasterId = role.extra["channelMasterId"]
                    ?: return CheckInResult.Failure("ROLE_INVALID", "角色缺少 channelMasterId")
                api.checkInArknights(role.uid, channelMasterId, currentSession)
            }
            "endfield" -> {
                val roleId = role.extra["roleId"]
                    ?: return CheckInResult.Failure("ROLE_INVALID", "终末地角色缺少 roleId")
                val serverId = role.extra["serverId"]
                    ?: return CheckInResult.Failure("ROLE_INVALID", "终末地角色缺少 serverId")
                api.checkInEndfield(roleId, serverId, currentSession)
            }
            else -> return CheckInResult.Failure("GAME_UNSUPPORTED", "暂不支持 ${game.displayName}")
        }
        return response.fold(
            onSuccess = SklandAttendanceResponse::parse,
            onFailure = {
                CheckInResult.Failure(
                    code = "NETWORK_OR_PROTOCOL",
                    message = it.message ?: "请求失败",
                    // 签到 POST 的响应读取失败时，服务端可能已经完成签到。
                    // 只在能确定请求尚未到达服务端的连接类错误上自动重试，避免重复提交。
                    retryable = SklandCheckInFailurePolicy.isSafeToRetry(it),
                )
            },
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

    private fun requireSession(): SklandSession =
        session ?: error("森空岛凭证尚未初始化")

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content
}

internal object SklandAttendanceResponse {
    fun parse(response: JsonObject): CheckInResult {
        // 森空岛不同接口/版本可能使用 code 或 status 表示结果码。
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

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content
}

internal object SklandCheckInFailurePolicy {
    fun isSafeToRetry(error: Throwable): Boolean = when (error) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException -> true

        else -> false
    }
}
