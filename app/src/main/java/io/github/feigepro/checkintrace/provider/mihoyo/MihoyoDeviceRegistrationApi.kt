package io.github.feigepro.checkintrace.provider.mihoyo

import android.os.Build
import io.github.feigepro.checkintrace.logging.DevLogger
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Android 米游社设备登记流程。
 *
 * 顺序与 nonebot-plugin-mystool 的 Android 签到实现保持一致：先 deviceLogin，
 * 再 saveDevice；两次请求以及后续签到都复用同一个持久化 device_id。
 */
class MihoyoDeviceRegistrationApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
    private val deviceProfile: MihoyoDeviceProfile = MihoyoDeviceProfile.current(),
) {
    suspend fun register(
        credential: MihoyoCredentialBundle,
        taskId: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            postDevice(DEVICE_LOGIN_URL, credential, "deviceLogin")
            postDevice(DEVICE_SAVE_URL, credential, "saveDevice")
            DevLogger.info("米游社/设备", "Android 设备登录与保存成功", taskId)
        }.onFailure {
            DevLogger.error("米游社/设备", it.message ?: "Android 设备注册失败", taskId)
        }
    }

    private fun postDevice(
        url: String,
        credential: MihoyoCredentialBundle,
        operation: String,
    ) {
        val body = buildString {
            append("{\"app_version\":\"")
            append(APP_VERSION)
            append("\",\"device_id\":\"")
            append(escape(credential.deviceId))
            append("\",\"device_name\":\"")
            append(escape(credential.deviceName?.takeIf(String::isNotBlank) ?: deviceProfile.name))
            append("\",\"os_version\":\"")
            append(Build.VERSION.SDK_INT)
            append("\",\"platform\":\"Android\",\"registration_id\":\"")
            append(REGISTRATION_ID)
            append("\"}")
        }
        val ds = MihoyoDsSigner.androidData(
            exactJsonBody = body,
            epochSeconds = Instant.now().epochSecond,
            randomNumber = random.nextInt(100_001) + 100_000,
        )
        val request = Request.Builder()
            .url(url)
            .headers(deviceHeaders(credential, ds))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = executeJson(request)
        val retcode = response["retcode"]?.jsonPrimitive?.content?.toIntOrNull()
        val message = response["message"]?.jsonPrimitive?.content.orEmpty()
        check(retcode == 0 || message == "OK") {
            "$operation 失败：retcode=$retcode message=${message.ifBlank { "未知错误" }}"
        }
    }

    private fun deviceHeaders(credential: MihoyoCredentialBundle, ds: String): Headers = Headers.Builder()
        .add("DS", ds)
        .add("x-rpc-client_type", "2")
        .add("x-rpc-app_version", APP_VERSION)
        .add("x-rpc-sys_version", credential.systemVersion?.takeIf(String::isNotBlank) ?: deviceProfile.systemVersion)
        .add("x-rpc-channel", "miyousheluodi")
        .add("x-rpc-device_id", credential.deviceId)
        .add("x-rpc-device_name", credential.deviceName?.takeIf(String::isNotBlank) ?: deviceProfile.name)
        .add("x-rpc-device_model", credential.deviceModel?.takeIf(String::isNotBlank) ?: deviceProfile.model)
        .add("Referer", "https://app.mihoyo.com")
        .add("Content-Type", "application/json; charset=UTF-8")
        .add("Connection", "Keep-Alive")
        .add("Accept-Encoding", "gzip")
        .add("User-Agent", "okhttp/4.9.3")
        .add("Cookie", credential.cookieHeader())
        .build()

    private fun executeJson(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        check(response.isSuccessful) { "HTTP ${response.code}" }
        check(raw.isNotBlank()) { "接口返回空内容" }
        json.parseToJsonElement(raw) as? JsonObject ?: error("接口未返回 JSON 对象")
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val DEVICE_LOGIN_URL = "https://bbs-api.mihoyo.com/apihub/api/deviceLogin"
        const val DEVICE_SAVE_URL = "https://bbs-api.mihoyo.com/apihub/api/saveDevice"
        const val APP_VERSION = "2.63.1"
        const val REGISTRATION_ID = "1a0018970a5c00e814d"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
