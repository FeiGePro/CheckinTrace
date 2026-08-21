package io.github.feigepro.checkintrace.provider.skland

import android.os.Build
import io.github.feigepro.checkintrace.logging.DevLogger
import io.github.feigepro.checkintrace.provider.ProviderFailureException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class SklandSession(val cred: String, val signToken: String)

class SklandApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun exchangeToken(token: String, taskId: String? = null): Result<SklandSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                DevLogger.debug("森空岛/登录", "开始交换登录凭证", taskId)
                val grantBody = "{\"appCode\":\"$APP_CODE\",\"token\":${jsonString(token)},\"type\":0}"
                val grant = postJson(GRANT_CODE_URL, grantBody, baseHeaders())
                requireApiSuccess(grant, "森空岛登录授权失败")
                val code = grant["data"]!!.jsonObject["code"]!!.jsonPrimitive.content

                val credBody = "{\"code\":${jsonString(code)},\"kind\":1}"
                val credResponse = postJson(CRED_CODE_URL, credBody, baseHeaders())
                requireApiSuccess(credResponse, "森空岛凭证交换失败")
                val data = credResponse["data"]!!.jsonObject
                DevLogger.info("森空岛/登录", "凭证交换成功", taskId)
                SklandSession(
                    cred = data["cred"]!!.jsonPrimitive.content,
                    signToken = data["token"]!!.jsonPrimitive.content,
                )
            }.onFailure {
                DevLogger.error("森空岛/登录", it.message ?: "凭证交换失败", taskId)
            }
        }

    suspend fun getBindings(session: SklandSession, taskId: String? = null): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                executeJsonWithRetry(
                    requestFactory = {
                        Request.Builder()
                            .url(BINDING_URL)
                            // Rebuild the signature on every attempt so a
                            // delayed retry never sends an expired timestamp.
                            .headers(signedHeaders(BINDING_URL, "", session))
                            .get()
                            .build()
                    },
                ).also {
                    requireApiSuccess(it, "森空岛角色查询失败")
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

    private suspend fun executeJsonWithRetry(requestFactory: () -> Request): JsonObject {
        var lastError: Exception? = null
        repeat(BINDING_MAX_ATTEMPTS) { attempt ->
            try {
                return executeJson(requestFactory())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                val retryable = error is IOException ||
                    (error as? ProviderFailureException)?.retryable == true
                if (!retryable || attempt == BINDING_MAX_ATTEMPTS - 1) throw error
                DevLogger.warn(
                    "森空岛/角色",
                    "角色查询暂时失败，${BINDING_RETRY_DELAYS_MILLIS[attempt]}ms 后重试（${error.message ?: error.javaClass.simpleName}）",
                )
                delay(BINDING_RETRY_DELAYS_MILLIS[attempt])
            }
        }
        throw lastError ?: IllegalStateException("角色查询失败")
    }

    private fun executeJson(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        val body = response.body ?: error("接口返回空内容")
        val raw = if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
            GZIPInputStream(body.byteStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            body.string()
        }
        val parsed = raw.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (!response.isSuccessful) {
            throw ProviderFailureException(
                code = when (response.code) {
                    401, 403 -> "AUTH_REQUIRED"
                    429 -> "RATE_LIMITED"
                    else -> "HTTP_${response.code}"
                },
                message = parsed?.let(::apiMessage) ?: "HTTP ${response.code}",
                retryable = isRetryableHttpCode(response.code),
            )
        }
        check(parsed != null) { "接口返回空内容或格式错误" }
        parsed
    }

    private fun baseHeaders() = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Accept-Encoding", "gzip")
        .add("Connection", "close")
        .build()

    private fun apiCode(value: JsonObject): Int =
        value["code"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: value["status"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: Int.MIN_VALUE

    private fun requireApiSuccess(value: JsonObject, prefix: String) {
        val code = apiCode(value)
        if (code == 0) return
        val mappedCode = when (code) {
            10000, 10002 -> "AUTH_REQUIRED"
            10001 -> "ALREADY_CHECKED_IN"
            else -> "API_$code"
        }
        throw ProviderFailureException(
            mappedCode,
            "$prefix：${apiMessage(value)}",
            retryable = code == 408 || code == 425 || code == 429 || code in 500..599,
        )
    }

    private fun apiMessage(value: JsonObject): String =
        value["message"]?.jsonPrimitive?.content
            ?: value["msg"]?.jsonPrimitive?.content
            ?: "未知接口错误"

    private fun jsonString(value: String): String = json.encodeToString(String.serializer(), value)

    private fun isRetryableHttpCode(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    companion object {
        const val ARKNIGHTS_ATTENDANCE_URL = "https://zonai.skland.com/api/v1/game/attendance"
        const val ENDFIELD_ATTENDANCE_URL = "https://zonai.skland.com/web/v1/game/endfield/attendance"
        private const val BINDING_URL = "https://zonai.skland.com/api/v1/game/player/binding"
        private const val CRED_CODE_URL = "https://zonai.skland.com/api/v1/user/auth/generate_cred_by_code"
        private const val GRANT_CODE_URL = "https://as.hypergryph.com/user/oauth2/v2/grant"
        private const val APP_CODE = "4ca99fa6b56cc2ba"
        private val USER_AGENT =
            "Skland/1.32.1 (com.hypergryph.skland; build:103201004; Android ${Build.VERSION.SDK_INT}; ) Okhttp/4.11.0"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val BINDING_MAX_ATTEMPTS = 3
        private val BINDING_RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_000L)

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
