package io.github.feigepro.checkintrace.provider.skland

import io.github.feigepro.checkintrace.logging.DevLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SklandQrSession(val scanId: String) {
    val qrContent: String get() = "hypergryph://scan_login?scanId=$scanId"
}

sealed interface SklandQrPollResult {
    data object Waiting : SklandQrPollResult
    data class Confirmed(val token: String) : SklandQrPollResult
    data class Failed(val message: String) : SklandQrPollResult
}

class SklandQrLoginClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun create(taskId: String? = null): Result<SklandQrSession> = withContext(Dispatchers.IO) {
        runCatching {
            val root = postJson(GENERATE_URL, "{\"appCode\":\"$APP_CODE\"}")
            requireSuccess(root, "获取登录二维码失败")
            val scanId = root["data"]!!.jsonObject["scanId"]!!.jsonPrimitive.content
            DevLogger.info("森空岛/登录", "二维码创建成功", taskId)
            SklandQrSession(scanId)
        }.onFailure { DevLogger.error("森空岛/登录", it.message ?: "二维码创建失败", taskId) }
    }

    suspend fun poll(session: SklandQrSession, taskId: String? = null): Result<SklandQrPollResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https").host("as.hypergryph.com")
                    .addPathSegments("general/v1/scan_status")
                    .addQueryParameter("scanId", session.scanId).build()
                val root = executeJson(Request.Builder().url(url).get().build())
                val status = root["status"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: error("二维码状态响应缺少 status")
                if (status != 0) {
                    val apiMessage = root["msg"]?.jsonPrimitive?.content
                        ?: root["message"]?.jsonPrimitive?.content
                        ?: "未知状态"
                    if (isTerminalQrStatus(status, apiMessage)) {
                        return@runCatching SklandQrPollResult.Failed("status=$status $apiMessage")
                    }
                    return@runCatching SklandQrPollResult.Waiting
                }
                val scanCode = root["data"]?.jsonObject?.get("scanCode")?.jsonPrimitive?.content
                    ?: error("扫码确认响应缺少 scanCode")
                DevLogger.info("森空岛/登录", "扫码确认成功，开始交换登录凭证", taskId)
                val tokenRoot = postJson(TOKEN_URL, "{\"scanCode\":${jsonString(scanCode)}}")
                requireSuccess(tokenRoot, "扫码登录凭证交换失败")
                val token = tokenRoot["data"]!!.jsonObject["token"]!!.jsonPrimitive.content
                SklandQrPollResult.Confirmed(token)
            }.onFailure { DevLogger.error("森空岛/登录", it.message ?: "轮询失败", taskId) }
        }

    private fun postJson(url: String, body: String): JsonObject = executeJson(
        Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
    )

    private fun executeJson(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        check(response.isSuccessful) { "HTTP ${response.code}" }
        check(raw.isNotBlank()) { "接口返回空内容" }
        json.parseToJsonElement(raw).jsonObject
    }

    private fun requireSuccess(root: JsonObject, prefix: String) {
        val status = root["status"]?.jsonPrimitive?.content?.toIntOrNull()
        check(status == 0) {
            "$prefix：${root["msg"]?.jsonPrimitive?.content ?: root["message"]?.jsonPrimitive?.content ?: "未知错误"}"
        }
    }

    private fun isTerminalQrStatus(status: Int, message: String): Boolean =
        status < 0 || listOf("过期", "失效", "拒绝", "expired", "invalid", "denied")
            .any { message.contains(it, ignoreCase = true) }

    private fun jsonString(value: String): String = json.encodeToString(String.serializer(), value)

    companion object {
        private const val APP_CODE = "4ca99fa6b56cc2ba"
        private const val GENERATE_URL = "https://as.hypergryph.com/general/v1/gen_scan/login"
        private const val TOKEN_URL = "https://as.hypergryph.com/user/auth/v1/token_by_scan_code"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
