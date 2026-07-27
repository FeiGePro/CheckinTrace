package io.github.feigepro.checkintrace.provider.skland

import io.github.feigepro.checkintrace.logging.DevLogger
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
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
)

data class SklandSession(val cred: String, val signToken: String)

class SklandAuthException(val apiCode: Int, message: String) : IllegalStateException(message)

class SklandApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun exchangeToken(token: String, taskId: String? = null): Result<SklandCredentialBundle> =
        withContext(Dispatchers.IO) {
            runCatching {
                DevLogger.debug("森空岛/登录", "开始交换登录凭证", taskId)
                val grantBody = "{\"appCode\":\"$APP_CODE\",\"token\":${jsonString(token)},\"type\":0}"
                val grant = postJson(GRANT_CODE_URL, grantBody, baseHeaders())
                requireApiSuccess(grant)
                val code = grant["data"]!!.jsonObject["code"]!!.jsonPrimitive.content

                val credBody = "{\"code\":${jsonString(code)},\"kind\":1}"
                val credResponse = postJson(CRED_CODE_URL, credBody, baseHeaders())
                requireApiSuccess(credResponse)
                val data = credResponse["data"]!!.jsonObject
                DevLogger.info("森空岛/登录", "凭证交换成功", taskId)
                SklandCredentialBundle(
                    accessToken = token,
                    cred = data["cred"]!!.jsonPrimitive.content,
                    signToken = data["token"]!!.jsonPrimitive.content,
                    userId = data["userId"]?.jsonPrimitive?.content,
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
                    .headers(baseHeaders().newBuilder().add("cred", cred).build())
                    .get()
                    .build()
                val root = executeJson(request)
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
                executeJson(request).also {
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
        val body = "{\"uid\": ${jsonString(uid)}, \"gameId\": ${jsonString(channelMasterId)}}"
        return checkInPost(ARKNIGHTS_ATTENDANCE_URL, body, body, session, null, taskId)
    }

    suspend fun checkInEndfield(
        roleId: String,
        serverId: String,
        session: SklandSession,
        taskId: String? = null,
    ): Result<JsonObject> = checkInPost(
        endpoint = ENDFIELD_ATTENDANCE_URL,
        body = "",
        signingBody = "",
        session = session,
        extraHeaders = mapOf("sk-game-role" to "3_${roleId}_${serverId}"),
        taskId = taskId,
    )

    private suspend fun checkInPost(
        endpoint: String,
        body: String,
        signingBody: String,
        session: SklandSession,
        extraHeaders: Map<String, String>?,
        taskId: String?,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val headers = signedHeaders(endpoint, signingBody, session).newBuilder()
                .add("Content-Type", "application/json")
                .apply { extraHeaders?.forEach { (name, value) -> add(name, value) } }
                .build()
            val request = Request.Builder()
                .url(endpoint)
                .headers(headers)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            executeJson(request).also {
                DevLogger.info("森空岛/签到", "接口响应 code=${apiCode(it)}", taskId)
            }
        }.onFailure {
            DevLogger.error("森空岛/签到", it.message ?: "签到请求失败", taskId)
        }
    }

    private fun signedHeaders(url: String, bodyOrQuery: String, session: SklandSession): Headers {
        val path = url.toHttpUrl().encodedPath
        val signed = SklandSigner.sign(path, bodyOrQuery, session.signToken, Instant.now().epochSecond)
        return baseHeaders().newBuilder()
            .add("cred", session.cred)
            .add("sign", signed.sign)
            .add("timestamp", signed.timestamp)
            .add("platform", signed.platform)
            .add("dId", signed.deviceId)
            .add("vName", signed.versionName)
            .build()
    }

    private fun postJson(url: String, body: String, headers: Headers): JsonObject {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJson(request)
    }

    private fun executeJson(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        val body = response.body ?: error("接口返回空内容")
        val raw = if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
            GZIPInputStream(body.byteStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            body.string()
        }
        check(raw.isNotBlank()) { "接口返回空内容" }
        json.parseToJsonElement(raw).jsonObject
    }

    private fun baseHeaders() = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Accept-Encoding", "gzip")
        .add("Connection", "close")
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
        private const val USER_AGENT =
            "Skland/1.32.1 (com.hypergryph.skland; build:103201004; Android 33; ) Okhttp/4.11.0"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
