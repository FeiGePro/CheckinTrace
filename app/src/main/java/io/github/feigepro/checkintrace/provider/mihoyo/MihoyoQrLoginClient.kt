package io.github.feigepro.checkintrace.provider.mihoyo

import android.content.Context
import io.github.feigepro.checkintrace.logging.DevLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.time.Instant
import java.io.IOException
import java.util.concurrent.TimeUnit

data class MihoyoQrSession(
    val url: String,
    val ticket: String,
    val deviceId: String,
    val deviceFp: String,
)

data class MihoyoDeviceIdentity(val deviceId: String, val deviceFp: String)

/** Keeps the generated device context stable across QR re-logins on one install. */
class MihoyoDeviceIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun getOrCreate(): MihoyoDeviceIdentity {
        synchronized(preferences) {
            val existingId = preferences.getString(DEVICE_ID_KEY, null)
            val existingFp = preferences.getString(DEVICE_FP_KEY, null)
            if (!existingId.isNullOrBlank() && !existingFp.isNullOrBlank()) {
                return MihoyoDeviceIdentity(existingId, existingFp)
            }
            val identity = MihoyoDeviceIdentity(
                deviceId = java.util.UUID.randomUUID().toString().uppercase(),
                deviceFp = buildString(13) {
                    repeat(13) { append("0123456789abcdef"[random.nextInt(16)]) }
                },
            )
            check(
                preferences.edit()
                    .putString(DEVICE_ID_KEY, identity.deviceId)
                    .putString(DEVICE_FP_KEY, identity.deviceFp)
                    .commit(),
            ) { "保存米游社设备上下文失败" }
            return identity
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "mihoyo_device_identity"
        const val DEVICE_ID_KEY = "device_id"
        const val DEVICE_FP_KEY = "device_fp"
    }
}

sealed interface MihoyoQrState {
    data object Waiting : MihoyoQrState
    data object Scanned : MihoyoQrState
    data class Confirmed(val accountId: String, val mid: String, val stoken: String) : MihoyoQrState
    data class Failed(val message: String) : MihoyoQrState
}

@Serializable
data class MihoyoCredentialBundle(
    val accountId: String,
    val mid: String,
    val stoken: String,
    val ltoken: String?,
    val cookieToken: String?,
    val deviceId: String,
    val deviceFp: String,
    val deviceModel: String? = null,
    val deviceName: String? = null,
    val systemVersion: String? = null,
) {
    fun cookieHeader(): String = buildList {
        add("account_id=$accountId")
        add("account_id_v2=$accountId")
        add("account_mid_v2=$mid")
        add("stuid=$accountId")
        add("stoken=$stoken")
        add("stoken_v2=$stoken")
        // 手机签到不发送 ltoken，避免沿用旧版电脑类型交换得到的凭证。
        cookieToken?.let { add("cookie_token=$it"); add("cookie_token_v2=$it") }
        add("mid=$mid")
    }.joinToString("; ")
}

/**
 * Android 移植自 MiyoQian 的 QRLogin/PassportLogin：请求地址、请求头、DS、
 * 状态机保持一致；凭证交换仅请求 Android 签到必需的 cookie_token。
 */
class MihoyoQrLoginClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
    private val deviceProfile: MihoyoDeviceProfile = MihoyoDeviceProfile.current(),
    private val deviceIdentity: MihoyoDeviceIdentity = MihoyoDeviceIdentity(
        deviceId = java.util.UUID.randomUUID().toString().uppercase(),
        deviceFp = buildString(13) {
            repeat(13) { append("0123456789abcdef"[SecureRandom().nextInt(16)]) }
        },
    ),
) {
    suspend fun createQr(taskId: String? = null): Result<MihoyoQrSession> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = deviceIdentity.deviceId
            val deviceFp = deviceIdentity.deviceFp
            val body = "{}"
            val response = postJson(QR_FETCH_URL, body, qrHeaders(deviceId, deviceFp, body))
            ensureSuccess(response, "生成二维码失败")
            val data = response["data"]!!.jsonObject
            val url = data["url"]?.jsonPrimitive?.content.orEmpty()
            val ticket = data["ticket"]?.jsonPrimitive?.content.orEmpty()
            check(url.isNotBlank() && ticket.isNotBlank()) { "二维码接口未返回 url/ticket" }
            DevLogger.info("米游社/登录", "通行证二维码创建成功", taskId)
            MihoyoQrSession(url, ticket, deviceId, deviceFp)
        }.onFailure {
            DevLogger.error("米游社/登录", it.message ?: "二维码创建失败", taskId)
        }
    }

    suspend fun queryQr(session: MihoyoQrSession, taskId: String? = null): Result<MihoyoQrState> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = "{\"ticket\":${jsonString(session.ticket)}}"
                val response = postJson(
                    QR_QUERY_URL,
                    body,
                    qrHeaders(session.deviceId, session.deviceFp, body),
                )
                val code = retcode(response)
                if (code != 0) {
                    val apiMessage = message(response)
                    DevLogger.warn("米游社/登录", "查询二维码状态失败 retcode=$code message=$apiMessage", taskId)
                    if (code == QR_EXPIRED_RETCODE || isTerminalQrMessage(apiMessage)) {
                        return@runCatching MihoyoQrState.Failed("retcode=$code $apiMessage")
                    }
                    throw IOException("二维码状态暂时不可用：retcode=$code $apiMessage")
                }
                val data = response["data"]!!.jsonObject
                when (val status = data["status"]?.jsonPrimitive?.content.orEmpty()) {
                    "Init", "Created" -> MihoyoQrState.Waiting
                    "Scanned" -> MihoyoQrState.Scanned.also {
                        DevLogger.info("米游社/登录", "已扫码，请在米游社确认登录", taskId)
                    }
                    "Confirmed" -> {
                        val userInfo = data["user_info"]?.jsonObject ?: error("扫码结果缺少 user_info")
                        val tokens = data["tokens"]?.jsonArray.orEmpty()
                        val tokenObjects = tokens.mapNotNull { it.jsonObjectOrNull() }
                        val stoken = tokenObjects
                            .firstOrNull { tokenName(it).contains("stoken") }
                            ?.get("token")?.jsonPrimitive?.content
                            ?: tokenObjects.singleOrNull()?.get("token")?.jsonPrimitive?.content
                            ?: ""
                        val mid = userInfo["mid"]?.jsonPrimitive?.content.orEmpty()
                        val aid = userInfo["aid"]?.jsonPrimitive?.content.orEmpty()
                        check(stoken.isNotBlank() && mid.isNotBlank() && aid.isNotBlank()) {
                            "扫码结果缺少 stoken/mid/aid"
                        }
                        DevLogger.info("米游社/登录", "扫码确认成功，开始交换签到凭证", taskId)
                        MihoyoQrState.Confirmed(aid, mid, stoken)
                    }
                    else -> MihoyoQrState.Failed("未知二维码状态：$status").also {
                        DevLogger.warn("米游社/登录", it.message, taskId)
                    }
                }
            }.onFailure {
                DevLogger.error("米游社/登录", it.message ?: "二维码状态查询失败", taskId)
            }
        }

    suspend fun exchangeCredential(
        qrSession: MihoyoQrSession,
        confirmed: MihoyoQrState.Confirmed,
        taskId: String? = null,
    ): Result<MihoyoCredentialBundle> = withContext(Dispatchers.IO) {
        runCatching {
            // 签到只需要 cookie_token。ltoken 接口在上游实现中使用桌面/其它客户端类型，
            // 手机版不请求它，避免生成不符合当前 Android 设备的登录记录。
            val ltoken: String? = null
            val cookieToken = exchangeStoken(
                GET_COOKIE_TOKEN_URL,
                "cookie_token",
                confirmed.stoken,
                confirmed.mid,
                qrSession,
            )
            check(cookieToken.isNotBlank()) { "stoken 换 cookie_token 失败：接口未返回 token" }
            DevLogger.info("米游社/登录", "登录凭证交换成功", taskId)
            MihoyoCredentialBundle(
                confirmed.accountId,
                confirmed.mid,
                confirmed.stoken,
                ltoken,
                cookieToken,
                qrSession.deviceId,
                qrSession.deviceFp,
                deviceProfile.model,
                deviceProfile.name,
                deviceProfile.systemVersion,
            )
        }.onFailure {
            DevLogger.error("米游社/登录", it.message ?: "登录凭证交换失败", taskId)
        }
    }

    private fun exchangeStoken(
        url: String,
        resultKey: String,
        stoken: String,
        mid: String,
        qrSession: MihoyoQrSession,
    ): String {
        val requestUrl = url.toHttpUrl().newBuilder()
            .addQueryParameter("stoken", stoken)
            .build()
        val query = requestUrl.encodedQuery.orEmpty()
        val ds = MihoyoDsSigner.x4(
            query = query,
            epochSeconds = Instant.now().epochSecond,
            randomNumber = random.nextInt(100_001) + 100_000,
        )
        val headers = Headers.Builder()
            .add("User-Agent", deviceProfile.userAgent(BBS_VERSION))
            .add("x-rpc-app_version", BBS_VERSION)
            .add("x-rpc-client_type", "2")
            .add("x-requested-with", "com.mihoyo.hyperion")
            .add("Referer", "https://webstatic.mihoyo.com")
            .add("x-rpc-device_id", qrSession.deviceId)
            .add("x-rpc-device_fp", qrSession.deviceFp)
            .add("x-rpc-device_model", deviceProfile.model)
            .add("x-rpc-device_name", deviceProfile.name)
            .add("x-rpc-sys_version", deviceProfile.systemVersion)
            .add("DS", ds)
            .add("Cookie", "mid=$mid;stoken=$stoken")
            .add("x-rpc-aigis", "")
            .build()
        val request = Request.Builder().url(requestUrl).headers(headers).get().build()
        val response = executeJson(request)
        ensureSuccess(response, "stoken 换 $resultKey 失败")
        return response["data"]?.jsonObject?.get(resultKey)?.jsonPrimitive?.content.orEmpty()
    }

    private fun qrHeaders(deviceId: String, deviceFp: String, exactBody: String): Headers {
        val ds = MihoyoDsSigner.x4(
            query = "",
            body = exactBody,
            epochSeconds = Instant.now().epochSecond,
            randomNumber = random.nextInt(100_001) + 100_000,
        )
        return Headers.Builder()
            .add("User-Agent", deviceProfile.userAgent(BBS_VERSION))
            .add("Accept", "*/*")
            .add("Accept-Language", "zh-cn")
            .add("x-rpc-client_type", "3")
            .add("x-rpc-app_version", PASSPORT_APP_VERSION)
            .add("x-rpc-device_id", deviceId)
            .add("x-rpc-device_fp", deviceFp)
            .add("x-rpc-game_biz", "bbs_cn")
            .add("x-rpc-app_id", PASSPORT_APP_ID)
            .add("x-rpc-sdk_version", PASSPORT_APP_VERSION)
            .add("x-rpc-device_model", deviceProfile.model)
            .add("x-rpc-device_name", deviceProfile.name)
            .add("x-rpc-account_version", PASSPORT_APP_VERSION)
            .add("DS", ds)
            .build()
    }

    private fun postJson(url: String, body: String, headers: Headers): JsonObject {
        val request = Request.Builder()
            .url(url)
            .headers(headers.newBuilder().set("Content-Type", "application/json; charset=UTF-8").build())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJson(request)
    }

    private fun executeJson(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        check(response.isSuccessful) { "HTTP ${response.code}" }
        check(body.isNotBlank()) { "接口返回空内容" }
        json.parseToJsonElement(body).jsonObject
    }

    private fun ensureSuccess(value: JsonObject, prefix: String) {
        val code = retcode(value)
        check(code == 0) { "$prefix: retcode=$code message=${message(value)}" }
    }

    private fun retcode(value: JsonObject): Int =
        value["retcode"]?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MIN_VALUE

    private fun message(value: JsonObject): String =
        value["message"]?.jsonPrimitive?.content ?: "未知接口错误"

    private fun isTerminalQrMessage(message: String): Boolean =
        listOf("过期", "失效", "超时", "拒绝", "已使用", "expired", "invalid", "denied")
            .any { message.contains(it, ignoreCase = true) }

    private fun jsonString(value: String): String = json.encodeToString(String.serializer(), value)

    private fun tokenName(value: JsonObject): String =
        value["name"]?.jsonPrimitive?.content.orEmpty().lowercase()

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    companion object {
        private const val QR_FETCH_URL = "https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin"
        private const val QR_QUERY_URL = "https://passport-api.mihoyo.com/account/ma-cn-passport/app/queryQRLoginStatus"

        private const val GET_COOKIE_TOKEN_URL = "https://passport-api.mihoyo.com/account/auth/api/getCookieAccountInfoBySToken"
        private const val PASSPORT_APP_ID = "bll8iq97cem8"
        private const val PASSPORT_APP_VERSION = "2.90.1"

        private const val BBS_VERSION = "2.106.2"
        private const val QR_EXPIRED_RETCODE = -106
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
