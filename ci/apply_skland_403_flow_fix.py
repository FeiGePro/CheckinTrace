from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.write_text(content, encoding="utf-8")


write(
    "app/src/main/java/io/github/feigepro/checkintrace/provider/skland/SklandApi.kt",
    r'''package io.github.feigepro.checkintrace.provider.skland

import io.github.feigepro.checkintrace.logging.DevLogger
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class SklandCredentialBundle(
    val accessToken: String,
    val cred: String? = null,
    val signToken: String? = null,
    val userId: String? = null,
    val signTokenUpdatedAtEpochSeconds: Long? = null,
)

data class SklandSession(val cred: String, val signToken: String)

class SklandAuthException(val apiCode: Int, message: String) : IllegalStateException(message)

class SklandForbiddenException(
    val operation: String,
    message: String,
) : IllegalStateException(message)

/**
 * 森空岛协议分层：
 * - 官方扫码 access token -> grant code -> cred/sign token；
 * - 每日首次使用时按需刷新 sign token；
 * - 角色与签到请求使用同一稳定 Android 请求画像和对应接口协议。
 */
class SklandApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun exchangeToken(token: String, taskId: String? = null): Result<SklandCredentialBundle> =
        withContext(Dispatchers.IO) {
            runCatching {
                DevLogger.debug("森空岛/登录", "开始交换登录凭证", taskId)
                val grantBody = "{\"appCode\":\"$APP_CODE\",\"token\":${jsonString(token)},\"type\":0}"
                val grant = postJson(
                    url = GRANT_CODE_URL,
                    body = grantBody,
                    headers = commonHeaders(),
                    operation = "获取 grant code",
                )
                requireApiSuccess(grant)
                val code = grant["data"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                    ?: error("grant 接口未返回 code")

                // 避免把两次登录交换压成同一瞬间的突发请求。
                delay(AUTH_PHASE_DELAY_MILLIS)
                val credBody = "{\"code\":${jsonString(code)},\"kind\":1}"
                val credResponse = postJson(
                    url = CRED_CODE_URL,
                    body = credBody,
                    headers = commonHeaders(),
                    operation = "生成 cred/sign token",
                )
                requireApiSuccess(credResponse)
                val data = credResponse["data"]?.jsonObject ?: error("凭证接口未返回 data")
                val cred = data["cred"]?.jsonPrimitive?.content.orEmpty()
                val signToken = data["token"]?.jsonPrimitive?.content.orEmpty()
                check(cred.isNotBlank() && signToken.isNotBlank()) { "凭证接口缺少 cred/sign token" }
                DevLogger.info("森空岛/登录", "凭证交换成功", taskId)
                SklandCredentialBundle(
                    accessToken = token,
                    cred = cred,
                    signToken = signToken,
                    userId = data["userId"]?.jsonPrimitive?.content,
                    signTokenUpdatedAtEpochSeconds = Instant.now().epochSecond,
                )
            }.onFailure {
                DevLogger.error("森空岛/登录", it.message ?: "凭证交换失败", taskId)
            }
        }

    suspend fun refreshSignToken(cred: String, taskId: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(REFRESH_URL)
                    .headers(commonHeaders().newBuilder().add("cred", cred).build())
                    .get()
                    .build()
                val root = executeJson(request, "刷新 sign token")
                requireApiSuccess(root)
                root["data"]?.jsonObject?.get("token")?.jsonPrimitive?.content
                    ?.takeIf(String::isNotBlank)
                    ?: error("刷新接口未返回 sign token")
            }.onSuccess {
                DevLogger.info("森空岛/登录", "签名凭证刷新成功", taskId)
            }.onFailure {
                DevLogger.warn("森空岛/登录", "签名凭证刷新失败：${it.message}", taskId)
            }
        }

    suspend fun getBindings(session: SklandSession, taskId: String? = null): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(BINDING_URL)
                    .headers(signedHeaders(BINDING_URL, "", session))
                    .get()
                    .build()
                executeJson(request, "读取绑定角色").also {
                    requireApiSuccess(it)
                    DevLogger.info("森空岛/角色", "绑定角色查询成功", taskId)
                }
            }.onFailure {
                DevLogger.error("森空岛/角色", it.message ?: "绑定角色查询失败", taskId)
            }
        }

    suspend fun checkInArknights(
        uid: String,
        channelMasterId: String,
        session: SklandSession,
        taskId: String? = null,
    ): Result<JsonObject> {
        val gameId = channelMasterId.toIntOrNull()?.toString() ?: jsonString(channelMasterId)
        val body = "{\"uid\":${jsonString(uid)},\"gameId\":$gameId}"
        return checkInPost(
            endpoint = ARKNIGHTS_ATTENDANCE_URL,
            body = body,
            session = session,
            taskId = taskId,
            operation = "明日方舟签到",
        )
    }

    suspend fun checkInEndfield(
        roleId: String,
        serverId: String,
        session: SklandSession,
        taskId: String? = null,
    ): Result<JsonObject> {
        val gameRole = "3_${roleId}_${serverId}"
        return checkInPost(
            endpoint = ENDFIELD_ATTENDANCE_URL,
            body = "",
            session = session,
            taskId = taskId,
            operation = "终末地签到",
            additionalHeaders = Headers.Builder()
                .add("sk-game-role", gameRole)
                .build(),
        )
    }

    private suspend fun checkInPost(
        endpoint: String,
        body: String,
        session: SklandSession,
        taskId: String?,
        operation: String,
        additionalHeaders: Headers = Headers.headersOf(),
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val headerBuilder = signedHeaders(endpoint, body, session).newBuilder()
                .add("Content-Type", "application/json")
            additionalHeaders.forEach { (name, value) -> headerBuilder.add(name, value) }
            val request = Request.Builder()
                .url(endpoint)
                .headers(headerBuilder.build())
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            executeJson(request, operation).also {
                DevLogger.info("森空岛/签到", "$operation 接口响应 code=${apiCode(it)}", taskId)
            }
        }.onFailure {
            DevLogger.error("森空岛/签到", it.message ?: "$operation 请求失败", taskId)
        }
    }

    private fun signedHeaders(url: String, bodyOrQuery: String, session: SklandSession): Headers {
        val path = url.toHttpUrl().encodedPath
        val signed = SklandSigner.sign(path, bodyOrQuery, session.signToken, Instant.now().epochSecond)
        return commonHeaders().newBuilder()
            .add("cred", session.cred)
            .add("sign", signed.sign)
            .add("timestamp", signed.timestamp)
            .add("platform", signed.platform)
            .add("dId", signed.deviceId)
            .add("vName", signed.versionName)
            .build()
    }

    private fun postJson(url: String, body: String, headers: Headers, operation: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJson(request, operation)
    }

    private fun executeJson(request: Request, operation: String): JsonObject =
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val parsed = raw.takeIf(String::isNotBlank)?.let { body ->
                runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            }
            val serverMessage = parsed?.let(::apiMessage)
                ?.takeUnless { it == "未知接口错误" }
            if (response.code == 401) {
                throw SklandAuthException(response.code, serverMessage ?: "HTTP 401")
            }
            if (response.code == 403) {
                val suffix = serverMessage?.let { "：$it" }.orEmpty()
                throw SklandForbiddenException(
                    operation = operation,
                    message = "$operation 被森空岛拒绝（HTTP 403）$suffix",
                )
            }
            check(response.isSuccessful) { "$operation 失败（HTTP ${response.code}）" }
            check(raw.isNotBlank()) { "$operation 返回空内容" }
            parsed ?: json.parseToJsonElement(raw).jsonObject
        }

    private fun commonHeaders(): Headers = Headers.Builder()
        .add("User-Agent", SKLAND_USER_AGENT)
        .add("Accept", "application/json")
        .build()

    private fun requireApiSuccess(value: JsonObject) {
        val code = apiCode(value)
        if (code == 0) return
        val message = apiMessage(value)
        if (code == 10000 || code == 10002) throw SklandAuthException(code, message)
        error(message)
    }

    private fun apiCode(value: JsonObject): Int =
        value["status"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: value["code"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: Int.MIN_VALUE

    private fun apiMessage(value: JsonObject): String =
        value["message"]?.jsonPrimitive?.content
            ?: value["msg"]?.jsonPrimitive?.content
            ?: "未知接口错误"

    private fun jsonString(value: String): String = json.encodeToString(String.serializer(), value)

    companion object {
        const val ARKNIGHTS_ATTENDANCE_URL = "https://zonai.skland.com/api/v1/game/attendance"
        const val ENDFIELD_ATTENDANCE_URL = "https://zonai.skland.com/web/v1/game/endfield/attendance"
        private const val BINDING_URL = "https://zonai.skland.com/api/v1/game/player/binding"
        private const val CRED_CODE_URL = "https://zonai.skland.com/api/v1/user/auth/generate_cred_by_code"
        private const val GRANT_CODE_URL = "https://as.hypergryph.com/user/oauth2/v2/grant"
        private const val REFRESH_URL = "https://zonai.skland.com/api/v1/auth/refresh"
        private const val APP_CODE = "4ca99fa6b56cc2ba"
        private const val AUTH_PHASE_DELAY_MILLIS = 800L
        internal const val SKLAND_USER_AGENT =
            "Skland/1.32.1 (com.hypergryph.skland; build:103201004; Android 33; ) Okhttp/4.11.0"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
''',
)

write(
    "app/src/main/java/io/github/feigepro/checkintrace/provider/skland/SklandProvider.kt",
    r'''package io.github.feigepro.checkintrace.provider.skland

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
''',
)

write(
    "app/src/test/java/io/github/feigepro/checkintrace/provider/skland/SklandApiAuthTest.kt",
    r'''package io.github.feigepro.checkintrace.provider.skland

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SklandApiAuthTest {
    @Test
    fun http401IsMappedToRecoverableAuthenticationFailure() = runTest {
        val client = responseClient(401, "Unauthorized", """{"code":10000,"message":"签名凭证已过期"}""")
        val api = SklandApi(client = client)

        val result = api.getBindings(SklandSession(cred = "cred", signToken = "expired-token"))
        val error = result.exceptionOrNull()

        assertTrue(error is SklandAuthException)
        assertEquals(401, (error as SklandAuthException).apiCode)
        assertEquals("签名凭证已过期", error.message)
    }

    @Test
    fun http403StopsInsteadOfEnteringAuthenticationRetryLoop() = runTest {
        val client = responseClient(403, "Forbidden", """{"message":"request forbidden"}""")
        val api = SklandApi(client = client)

        val result = api.getBindings(SklandSession(cred = "cred", signToken = "token"))
        val error = result.exceptionOrNull()

        assertTrue(error is SklandForbiddenException)
        assertEquals("读取绑定角色", (error as SklandForbiddenException).operation)
        assertTrue(error.message.orEmpty().contains("HTTP 403"))
    }

    @Test
    fun endfieldUsesWebEndpointGameRoleHeaderAndEmptyBody() = runTest {
        val captured = AtomicReference<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured.set(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"code":0,"data":{}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = SklandApi(client = client)

        val result = api.checkInEndfield(
            roleId = "role-1",
            serverId = "server-2",
            session = SklandSession(cred = "cred", signToken = "token"),
        )

        assertTrue(result.isSuccess)
        val request = captured.get()
        assertEquals("/web/v1/game/endfield/attendance", request.url.encodedPath)
        assertEquals("3_role-1_server-2", request.header("sk-game-role"))
        assertEquals(SklandApi.SKLAND_USER_AGENT, request.header("User-Agent"))
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        assertEquals("", body)
    }

    private fun responseClient(code: Int, message: String, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(message)
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
}
''',
)

main_path = ROOT / "app/src/main/java/io/github/feigepro/checkintrace/MainViewModel.kt"
main = main_path.read_text(encoding="utf-8")
main = main.replace(
    "import io.github.feigepro.checkintrace.provider.skland.SklandProvider\n",
    "import io.github.feigepro.checkintrace.provider.skland.SklandForbiddenException\n"
    "import io.github.feigepro.checkintrace.provider.skland.SklandProvider\n",
    1,
)
old_role = '''                    if (roleResult.isFailure) {
                        lines += "${nowLabel()} ${game.displayName}：读取角色失败（${roleResult.exceptionOrNull()?.message}）"
                        continue
                    }'''
new_role = '''                    if (roleResult.isFailure) {
                        val error = roleResult.exceptionOrNull()
                        lines += "${nowLabel()} ${game.displayName}：读取角色失败（${error?.message}）"
                        if (error is SklandForbiddenException) {
                            lines += "${nowLabel()} 森空岛返回 HTTP 403，已停止后续请求，请勿连续重试"
                            stopProvider = true
                            break
                        }
                        continue
                    }'''
if old_role not in main:
    raise SystemExit("MainViewModel role failure block not found")
main = main.replace(old_role, new_role, 1)
old_stop = '''                                if (result.code == "CAPTCHA_REQUIRED") {
                                    lines += "${nowLabel()} 检测到人工验证要求，已停止${providerName(providerType)}后续请求"
                                    stopProvider = true
                                    break
                                }'''
new_stop = '''                                if (result.code in STOP_PROVIDER_CODES) {
                                    lines += "${nowLabel()} ${providerName(providerType)}需要人工处理，已停止后续请求"
                                    stopProvider = true
                                    break
                                }'''
if old_stop not in main:
    raise SystemExit("MainViewModel stop block not found")
main = main.replace(old_stop, new_stop, 1)
main = main.replace(
    '        const val DEFAULT_ACCOUNT = "default"\n',
    '        const val DEFAULT_ACCOUNT = "default"\n'
    '        val STOP_PROVIDER_CODES = setOf("CAPTCHA_REQUIRED", "AUTH_REQUIRED", "RISK_BLOCKED")\n',
    1,
)
main_path.write_text(main, encoding="utf-8")

worker_path = ROOT / "app/src/main/java/io/github/feigepro/checkintrace/AutoCheckInWorker.kt"
worker = worker_path.read_text(encoding="utf-8")
worker = worker.replace(
    "import io.github.feigepro.checkintrace.provider.skland.SklandProvider\n",
    "import io.github.feigepro.checkintrace.provider.skland.SklandForbiddenException\n"
    "import io.github.feigepro.checkintrace.provider.skland.SklandProvider\n",
    1,
)
old_worker_role = '''                    if (roleResult.isFailure) {
                        val error = roleResult.exceptionOrNull()
                        val message = safeMessage(error?.message ?: "角色读取失败")
                        lines += timestamped("${game.displayName}：读取角色失败（$message）")
                        DevLogger.error("自动任务/${game.displayName}", message, taskId)
                        hasFailures = true
                        if (isTransientError(error)) hasRetryableFailure = true else requiresAction = true
                        continue
                    }'''
new_worker_role = '''                    if (roleResult.isFailure) {
                        val error = roleResult.exceptionOrNull()
                        val message = safeMessage(error?.message ?: "角色读取失败")
                        lines += timestamped("${game.displayName}：读取角色失败（$message）")
                        DevLogger.error("自动任务/${game.displayName}", message, taskId)
                        hasFailures = true
                        if (isTransientError(error)) hasRetryableFailure = true else requiresAction = true
                        if (error is SklandForbiddenException) {
                            lines += timestamped("森空岛返回 HTTP 403，已停止后续请求，不安排自动重试")
                            stopProvider = true
                            break
                        }
                        continue
                    }'''
if old_worker_role not in worker:
    raise SystemExit("AutoCheckInWorker role failure block not found")
worker = worker.replace(old_worker_role, new_worker_role, 1)
old_worker_stop = '''                                if (result.code == "CAPTCHA_REQUIRED") {
                                    lines += timestamped("检测到人工验证要求，已停止${providerName(type)}后续请求")
                                    stopProvider = true
                                    break
                                }'''
new_worker_stop = '''                                if (result.code in STOP_PROVIDER_CODES) {
                                    lines += timestamped("${providerName(type)}需要人工处理，已停止后续请求")
                                    stopProvider = true
                                    break
                                }'''
if old_worker_stop not in worker:
    raise SystemExit("AutoCheckInWorker stop block not found")
worker = worker.replace(old_worker_stop, new_worker_stop, 1)
worker = worker.replace(
    '        val ACTION_REQUIRED_CODES = setOf("CAPTCHA_REQUIRED", "AUTH_REQUIRED", "FIRST_BIND_REQUIRED")\n',
    '        val ACTION_REQUIRED_CODES = setOf("CAPTCHA_REQUIRED", "AUTH_REQUIRED", "FIRST_BIND_REQUIRED", "RISK_BLOCKED")\n'
    '        val STOP_PROVIDER_CODES = setOf("CAPTCHA_REQUIRED", "AUTH_REQUIRED", "RISK_BLOCKED")\n',
    1,
)
worker_path.write_text(worker, encoding="utf-8")

print("Skland three-phase flow patch applied")
