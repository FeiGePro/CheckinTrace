package io.github.feigepro.checkintrace.provider.skland

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
