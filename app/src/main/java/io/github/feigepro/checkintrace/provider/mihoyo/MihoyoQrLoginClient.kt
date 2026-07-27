package io.github.feigepro.checkintrace.provider.mihoyo

import io.github.feigepro.checkintrace.logging.DevLogger
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class MihoyoDeviceIdentity(
    val deviceId: String,
    val deviceFp: String,
)

data class MihoyoQrSession(
    val url: String,
    val ticket: String,
    val deviceId: String,
    val deviceFp: String,
)

sealed interface MihoyoQrState {
    data object Waiting : MihoyoQrState
    data object Scanned : MihoyoQrState

    /**
     * The legacy property name is retained for source compatibility. In the passport QR flow it stores stoken_v2.
     */
    data class Confirmed(
        val accountId: String,
        val gameToken: String,
        val mid: String? = null,
    ) : MihoyoQrState

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
        cookieToken?.let { add("cookie_token=$it"); add("cookie_token_v2=$it") }
        add("mid=$mid")
    }.joinToString("; ")
}

/**
 * Primary miHoYo login restored to the previously verified passport QR flow.
 *
 * This QR authorizes a passport/desktop-style session. It is deliberately kept separate from the
 * Android attendance flow: MihoyoProvider performs Android deviceLogin/saveDevice before attendance.
 */
class MihoyoQrLoginClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
    private val deviceProfile: MihoyoDeviceProfile = MihoyoDeviceProfile.current(),
    private val deviceIdentity: MihoyoDeviceIdentity = generateDeviceIdentity(),
) {
    suspend fun createQr(taskId: String? = null): Result<MihoyoQrSession> = withContext(Dispatchers.IO) {
        runCatching {
            val body = "{}"
            val response = postJson(
                PASSPORT_QR_FETCH_URL,
                body,
                passportQrHeaders(deviceIdentity.deviceId, deviceIdentity.deviceFp, body),
            )
            ensureSuccess(response, "生成米游社通行证二维码失败")
            val data = response["data"]?.jsonObject ?: error("二维码接口未返回 data")
            val url = data["url"]?.jsonPrimitive?.content.orEmpty()
            val ticket = data["ticket"]?.jsonPrimitive?.content.orEmpty()
            check(url.isNotBlank() && ticket.isNotBlank()) { "二维码接口未返回 url/ticket" }
            DevLogger.info("米游社/登录", "通行证二维码创建成功", taskId)
            MihoyoQrSession(
                url = url,
                ticket = ticket,
                deviceId = deviceIdentity.deviceId,
                deviceFp = deviceIdentity.deviceFp,
            )
        }.onFailure {
            DevLogger.error("米游社/登录", it.message ?: "二维码创建失败", taskId)
        }
    }

    suspend fun queryQr(
        session: MihoyoQrSession,
        taskId: String? = null,
    ): Result<MihoyoQrState> = withContext(Dispatchers.IO) {
        runCatching {
            val body = "{\"ticket\":${jsonString(session.ticket)}}"
            val response = postJson(
                PASSPORT_QR_QUERY_URL,
                body,
                passportQrHeaders(session.deviceId, session.deviceFp, body),
            )
            val code = retcode(response)
            if (code != 0) return@runCatching MihoyoQrState.Failed("retcode=$code ${message(response)}")
            val data = response["data"]?.jsonObject ?: return@runCatching MihoyoQrState.Waiting
            when (val status = data["status"]?.jsonPrimitive?.content.orEmpty()) {
                "Init", "Created" -> MihoyoQrState.Waiting
                "Scanned" -> MihoyoQrState.Scanned
                "Confirmed" -> {
                    val userInfo = data["user_info"]?.jsonObject ?: error("扫码结果缺少 user_info")
                    val tokens = data["tokens"]?.jsonArray.orEmpty()
                    val stoken = tokens.firstOrNull()
                        ?.jsonObject
                        ?.get("token")
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                    val mid = userInfo["mid"]?.jsonPrimitive?.content.orEmpty()
                    val aid = userInfo["aid"]?.jsonPrimitive?.content.orEmpty()
                    check(stoken.isNotBlank() && mid.isNotBlank() && aid.isNotBlank()) {
                        "扫码结果缺少 stoken/mid/aid"
                    }
                    DevLogger.info("米游社/登录", "通行证扫码确认成功，开始交换签到凭证", taskId)
                    MihoyoQrState.Confirmed(accountId = aid, gameToken = stoken, mid = mid)
                }
                else -> MihoyoQrState.Failed("未知通行证二维码状态：$status")
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
            val mid = confirmed.mid ?: error("通行证扫码结果缺少 mid")
            val stoken = confirmed.gameToken
            val cookieToken = exchangeCookieToken(stoken, mid, qrSession)
            check(cookieToken.isNotBlank()) { "stoken 换 cookie_token 失败：接口未返回 token" }
            DevLogger.info("米游社/登录", "通行证登录凭证交换成功", taskId)
            MihoyoCredentialBundle(
                accountId = confirmed.accountId,
                mid = mid,
                stoken = stoken,
                ltoken = null,
                cookieToken = cookieToken,
                deviceId = qrSession.deviceId,
                deviceFp = qrSession.deviceFp,
                deviceModel = deviceProfile.model,
                deviceName = deviceProfile.name,
                systemVersion = deviceProfile.systemVersion,
            )
        }.onFailure {
            DevLogger.error("米游社/登录", it.message ?: "登录凭证交换失败", taskId)
        }
    }

    private fun exchangeCookieToken(
        stoken: String,
        mid: String,
        session: MihoyoQrSession,
    ): String {
        val query = "stoken=$stoken"
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
            .add("x-rpc-device_id", session.deviceId)
            .add("x-rpc-device_fp", session.deviceFp)
            .add("x-rpc-device_model", deviceProfile.model)
            .add("x-rpc-device_name", deviceProfile.name)
            .add("x-rpc-sys_version", deviceProfile.systemVersion)
            .add("DS", ds)
            .add("Cookie", "mid=$mid;stoken=$stoken")
            .add("x-rpc-aigis", "")
            .build()
        val request = Request.Builder()
            .url("$GET_COOKIE_TOKEN_URL?$query")
            .headers(headers)
            .get()
            .build()
        val response = executeJson(request)
        ensureSuccess(response, "stoken 换 cookie_token 失败")
        return response["data"]?.jsonObject?.get("cookie_token")?.jsonPrimitive?.content.orEmpty()
    }

    private fun passportQrHeaders(deviceId: String, deviceFp: String, exactBody: String): Headers {
        val ds = MihoyoDsSigner.x4(
            query = "",
            body = exactBody,
            epochSeconds = Instant.now().epochSecond,
            randomNumber = random.nextInt(100_001) + 100_000,
        )
        return Headers.Builder()
            .add("User-Agent", PASSPORT_APP_UA)
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

    private fun postJson(
        url: String,
        body: String,
        headers: Headers = Headers.Builder().build(),
    ): JsonObject {
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

    private fun jsonString(value: String): String = json.encodeToString(String.serializer(), value)

    companion object {
        fun generateDeviceIdentity(random: SecureRandom = SecureRandom()): MihoyoDeviceIdentity = MihoyoDeviceIdentity(
            deviceId = UUID.randomUUID().toString().uppercase(),
            deviceFp = buildString(13) { repeat(13) { append("0123456789abcdef"[random.nextInt(16)]) } },
        )

        private const val PASSPORT_QR_FETCH_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin"
        private const val PASSPORT_QR_QUERY_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-passport/app/queryQRLoginStatus"
        private const val GET_COOKIE_TOKEN_URL =
            "https://passport-api.mihoyo.com/account/auth/api/getCookieAccountInfoBySToken"
        private const val PASSPORT_APP_ID = "bll8iq97cem8"
        private const val PASSPORT_APP_VERSION = "2.90.1"
        private const val PASSPORT_APP_UA = "Mozilla/5.0 miHoYoBBS/2.90.1 Capture/2.2.0"
        private const val BBS_VERSION = "2.106.2"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
