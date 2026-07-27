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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    data class Confirmed(val accountId: String, val gameToken: String) : MihoyoQrState
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
 * 米游社主登录流程。
 *
 * 完整采用 README 所列 nonebot-plugin-mystool 的 GameToken 二维码链路：
 * app_id=2 创建/轮询二维码，同一个持久化 device_id 换取 stoken 与 cookie_token，
 * 最后执行 Android deviceLogin/saveDevice。这里不再混入 Capture、网页或启动器二维码。
 */
class MihoyoQrLoginClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
    private val deviceProfile: MihoyoDeviceProfile = MihoyoDeviceProfile.current(),
    private val deviceIdentity: MihoyoDeviceIdentity = generateDeviceIdentity(),
    private val deviceRegistrationApi: MihoyoDeviceRegistrationApi = MihoyoDeviceRegistrationApi(),
) {
    suspend fun createQr(taskId: String? = null): Result<MihoyoQrSession> = withContext(Dispatchers.IO) {
        runCatching {
            val body = "{\"app_id\":\"$GAME_QR_APP_ID\",\"device\":${jsonString(deviceIdentity.deviceId)}}"
            val response = postJson(GAME_QR_FETCH_URL, body)
            ensureSuccess(response, "生成 GameToken 二维码失败")
            val url = response["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
            val ticket = url.toHttpUrlOrNull()?.queryParameter("ticket").orEmpty()
            check(url.isNotBlank() && ticket.isNotBlank()) { "二维码接口未返回 url/ticket" }
            DevLogger.info("米游社/登录", "Android GameToken 二维码创建成功", taskId)
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
            val body = buildString {
                append("{\"app_id\":\"")
                append(GAME_QR_APP_ID)
                append("\",\"device\":")
                append(jsonString(session.deviceId))
                append(",\"ticket\":")
                append(jsonString(session.ticket))
                append('}')
            }
            val response = postJson(GAME_QR_QUERY_URL, body)
            val code = retcode(response)
            if (code == -106) return@runCatching MihoyoQrState.Failed("二维码已过期")
            if (code != 0) return@runCatching MihoyoQrState.Failed("retcode=$code ${message(response)}")
            val data = response["data"]?.jsonObject ?: return@runCatching MihoyoQrState.Waiting
            when (val status = data["stat"]?.jsonPrimitive?.content.orEmpty()) {
                "Init", "Created" -> MihoyoQrState.Waiting
                "Scanned" -> MihoyoQrState.Scanned
                "Confirmed" -> {
                    val raw = data["payload"]?.jsonObject?.get("raw")?.jsonPrimitive?.content.orEmpty()
                    val payload = json.parseToJsonElement(raw).jsonObject
                    val uid = payload["uid"]?.jsonPrimitive?.content.orEmpty()
                    val gameToken = payload["token"]?.jsonPrimitive?.content.orEmpty()
                    check(uid.isNotBlank() && gameToken.isNotBlank()) {
                        "扫码结果缺少 uid/game_token"
                    }
                    DevLogger.info("米游社/登录", "扫码确认成功，开始交换 Android 登录凭证", taskId)
                    MihoyoQrState.Confirmed(uid, gameToken)
                }
                else -> MihoyoQrState.Failed("未知二维码状态：$status")
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
            val account = exchangeGameToken(confirmed.accountId, confirmed.gameToken, qrSession)
            val cookieToken = exchangeCookieToken(account.stoken, account.mid, qrSession)
            check(cookieToken.isNotBlank()) { "stoken 换 cookie_token 失败：接口未返回 token" }
            val credential = MihoyoCredentialBundle(
                accountId = account.accountId,
                mid = account.mid,
                stoken = account.stoken,
                ltoken = null,
                cookieToken = cookieToken,
                deviceId = qrSession.deviceId,
                deviceFp = qrSession.deviceFp,
                deviceModel = deviceProfile.model,
                deviceName = deviceProfile.name,
                systemVersion = deviceProfile.systemVersion,
            )
            deviceRegistrationApi.register(credential, taskId).getOrThrow()
            DevLogger.info("米游社/登录", "凭证交换及 Android 设备注册成功", taskId)
            credential
        }.onFailure {
            DevLogger.error("米游社/登录", it.message ?: "登录凭证交换失败", taskId)
        }
    }

    private fun exchangeGameToken(
        accountId: String,
        gameToken: String,
        session: MihoyoQrSession,
    ): TokenAccount {
        val numericId = accountId.toLongOrNull() ?: error("二维码返回了无效账号 ID")
        val body = "{\"account_id\":$numericId,\"game_token\":${jsonString(gameToken)}}"
        val ds = MihoyoDsSigner.k2(body, Instant.now().epochSecond, randomLetters(6))
        val headers = Headers.Builder()
            .add("x-rpc-app_id", PASSPORT_APP_ID)
            .add("x-rpc-client_type", "2")
            .add("x-rpc-game_biz", "bbs_cn")
            .add("x-rpc-device_id", session.deviceId)
            .add("x-rpc-device_fp", session.deviceFp)
            .add("x-rpc-device_model", deviceProfile.model)
            .add("x-rpc-device_name", deviceProfile.name)
            .add("x-rpc-sys_version", deviceProfile.systemVersion)
            .add("DS", ds)
            .add("User-Agent", deviceProfile.userAgent(BBS_VERSION))
            .add("Content-Type", "application/json")
            .build()
        val response = postJson(GET_TOKEN_BY_GAME_TOKEN_URL, body, headers)
        ensureSuccess(response, "game_token 换 stoken 失败")
        val data = response["data"]?.jsonObject ?: error("game_token 交换结果缺少 data")
        val token = data["token"]?.jsonObject?.get("token")?.jsonPrimitive?.content.orEmpty()
        val userInfo = data["user_info"]?.jsonObject ?: error("game_token 交换结果缺少 user_info")
        val mid = userInfo["mid"]?.jsonPrimitive?.content.orEmpty()
        val aid = userInfo["aid"]?.jsonPrimitive?.content.orEmpty().ifBlank { accountId }
        check(token.isNotBlank() && mid.isNotBlank()) { "game_token 交换结果缺少 stoken/mid" }
        return TokenAccount(aid, mid, token)
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

    private fun randomLetters(length: Int): String = buildString(length) {
        val values = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        repeat(length) { append(values[random.nextInt(values.length)]) }
    }

    private data class TokenAccount(val accountId: String, val mid: String, val stoken: String)

    companion object {
        fun generateDeviceIdentity(random: SecureRandom = SecureRandom()): MihoyoDeviceIdentity = MihoyoDeviceIdentity(
            deviceId = UUID.randomUUID().toString().uppercase(),
            deviceFp = buildString(13) { repeat(13) { append("0123456789abcdef"[random.nextInt(16)]) } },
        )

        private const val GAME_QR_FETCH_URL =
            "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/fetch"
        private const val GAME_QR_QUERY_URL =
            "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/query"
        private const val GET_TOKEN_BY_GAME_TOKEN_URL =
            "https://api-takumi.mihoyo.com/account/ma-cn-session/app/getTokenByGameToken"
        private const val GET_COOKIE_TOKEN_URL =
            "https://passport-api.mihoyo.com/account/auth/api/getCookieAccountInfoBySToken"
        private const val GAME_QR_APP_ID = "2"
        private const val PASSPORT_APP_ID = "bll8iq97cem8"
        private const val BBS_VERSION = "2.63.1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
