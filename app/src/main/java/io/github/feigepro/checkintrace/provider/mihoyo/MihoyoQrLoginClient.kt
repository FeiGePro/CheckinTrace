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

enum class MihoyoLoginMethod { GAME_QR, PASSPORT_QR }

enum class MihoyoTokenKind { GAME_TOKEN, STOKEN }

data class MihoyoQrSession(
    val url: String,
    val ticket: String,
    val deviceId: String,
    val deviceFp: String,
    val method: MihoyoLoginMethod = MihoyoLoginMethod.GAME_QR,
)

sealed interface MihoyoQrState {
    data object Waiting : MihoyoQrState
    data object Scanned : MihoyoQrState
    data class Confirmed(
        val accountId: String,
        val token: String,
        val tokenKind: MihoyoTokenKind,
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
 * 默认采用游戏 SDK 二维码登录：二维码授权只产生短期 game_token，再交换签到所需凭证。
 * 通行证二维码实现保留为协议备用，但不会在默认流程中自动切换，避免用户在不知情时改变授权类型。
 */
class MihoyoQrLoginClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
    private val deviceProfile: MihoyoDeviceProfile = MihoyoDeviceProfile.current(),
    private val deviceIdentity: MihoyoDeviceIdentity = generateDeviceIdentity(),
) {
    suspend fun createQr(taskId: String? = null): Result<MihoyoQrSession> = createGameQr(taskId)

    suspend fun createGameQr(taskId: String? = null): Result<MihoyoQrSession> = withContext(Dispatchers.IO) {
        runCatching {
            val body = "{\"app_id\":\"$GAME_QR_APP_ID\",\"device\":${jsonString(deviceIdentity.deviceId)}}"
            val response = postJson(GAME_QR_FETCH_URL, body)
            ensureSuccess(response, "生成游戏登录二维码失败")
            val url = response["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
            val ticket = url.toHttpUrlOrNull()?.queryParameter("ticket").orEmpty()
            check(url.isNotBlank() && ticket.isNotBlank()) { "游戏二维码接口未返回 url/ticket" }
            DevLogger.info("米游社/登录", "游戏登录二维码创建成功", taskId)
            MihoyoQrSession(
                url = url,
                ticket = ticket,
                deviceId = deviceIdentity.deviceId,
                deviceFp = deviceIdentity.deviceFp,
                method = MihoyoLoginMethod.GAME_QR,
            )
        }.onFailure { DevLogger.error("米游社/登录", it.message ?: "二维码创建失败", taskId) }
    }

    suspend fun createPassportQr(taskId: String? = null): Result<MihoyoQrSession> = withContext(Dispatchers.IO) {
        runCatching {
            val body = "{}"
            val response = postJson(
                PASSPORT_QR_FETCH_URL,
                body,
                passportQrHeaders(deviceIdentity.deviceId, deviceIdentity.deviceFp, body),
            )
            ensureSuccess(response, "生成通行证二维码失败")
            val data = response["data"]!!.jsonObject
            val url = data["url"]?.jsonPrimitive?.content.orEmpty()
            val ticket = data["ticket"]?.jsonPrimitive?.content.orEmpty()
            check(url.isNotBlank() && ticket.isNotBlank()) { "通行证二维码接口未返回 url/ticket" }
            MihoyoQrSession(
                url,
                ticket,
                deviceIdentity.deviceId,
                deviceIdentity.deviceFp,
                MihoyoLoginMethod.PASSPORT_QR,
            )
        }
    }

    suspend fun queryQr(session: MihoyoQrSession, taskId: String? = null): Result<MihoyoQrState> =
        withContext(Dispatchers.IO) {
            when (session.method) {
                MihoyoLoginMethod.GAME_QR -> queryGameQr(session, taskId)
                MihoyoLoginMethod.PASSPORT_QR -> queryPassportQr(session, taskId)
            }
        }

    private fun queryGameQr(session: MihoyoQrSession, taskId: String?): Result<MihoyoQrState> = runCatching {
        val body = "{\"app_id\":\"$GAME_QR_APP_ID\",\"device\":${jsonString(session.deviceId)},\"ticket\":${jsonString(session.ticket)}}"
        val response = postJson(GAME_QR_QUERY_URL, body)
        val code = retcode(response)
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
                check(uid.isNotBlank() && gameToken.isNotBlank()) { "游戏扫码结果缺少 uid/game_token" }
                DevLogger.info("米游社/登录", "游戏扫码确认成功，开始交换签到凭证", taskId)
                MihoyoQrState.Confirmed(uid, gameToken, MihoyoTokenKind.GAME_TOKEN)
            }
            else -> MihoyoQrState.Failed("未知游戏二维码状态：$status")
        }
    }.onFailure { DevLogger.error("米游社/登录", it.message ?: "二维码状态查询失败", taskId) }

    private fun queryPassportQr(session: MihoyoQrSession, taskId: String?): Result<MihoyoQrState> = runCatching {
        val body = "{\"ticket\":${jsonString(session.ticket)}}"
        val response = postJson(
            PASSPORT_QR_QUERY_URL,
            body,
            passportQrHeaders(session.deviceId, session.deviceFp, body),
        )
        val code = retcode(response)
        if (code != 0) return@runCatching MihoyoQrState.Failed("retcode=$code ${message(response)}")
        val data = response["data"]!!.jsonObject
        when (val status = data["status"]?.jsonPrimitive?.content.orEmpty()) {
            "Init", "Created" -> MihoyoQrState.Waiting
            "Scanned" -> MihoyoQrState.Scanned
            "Confirmed" -> {
                val userInfo = data["user_info"]?.jsonObject ?: error("扫码结果缺少 user_info")
                val tokens = data["tokens"]?.jsonArray.orEmpty()
                val stoken = tokens.firstOrNull()?.jsonObject?.get("token")?.jsonPrimitive?.content.orEmpty()
                val mid = userInfo["mid"]?.jsonPrimitive?.content.orEmpty()
                val aid = userInfo["aid"]?.jsonPrimitive?.content.orEmpty()
                check(stoken.isNotBlank() && mid.isNotBlank() && aid.isNotBlank()) { "扫码结果缺少 stoken/mid/aid" }
                MihoyoQrState.Confirmed(aid, stoken, MihoyoTokenKind.STOKEN, mid)
            }
            else -> MihoyoQrState.Failed("未知通行证二维码状态：$status")
        }
    }.onFailure { DevLogger.error("米游社/登录", it.message ?: "二维码状态查询失败", taskId) }

    suspend fun exchangeCredential(
        qrSession: MihoyoQrSession,
        confirmed: MihoyoQrState.Confirmed,
        taskId: String? = null,
    ): Result<MihoyoCredentialBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val account = when (confirmed.tokenKind) {
                MihoyoTokenKind.GAME_TOKEN -> exchangeGameToken(confirmed.accountId, confirmed.token, qrSession)
                MihoyoTokenKind.STOKEN -> TokenAccount(
                    accountId = confirmed.accountId,
                    mid = confirmed.mid ?: error("通行证扫码结果缺少 mid"),
                    stoken = confirmed.token,
                )
            }
            val cookieToken = exchangeStoken(
                GET_COOKIE_TOKEN_URL,
                "cookie_token",
                account.stoken,
                account.mid,
                qrSession,
            )
            check(cookieToken.isNotBlank()) { "stoken 换 cookie_token 失败：接口未返回 token" }
            DevLogger.info("米游社/登录", "登录凭证交换成功", taskId)
            MihoyoCredentialBundle(
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
        }.onFailure { DevLogger.error("米游社/登录", it.message ?: "登录凭证交换失败", taskId) }
    }

    private fun exchangeGameToken(accountId: String, gameToken: String, session: MihoyoQrSession): TokenAccount {
        val numericId = accountId.toLongOrNull() ?: error("游戏登录返回了无效账号 ID")
        val body = "{\"account_id\":$numericId,\"game_token\":${jsonString(gameToken)}}"
        val ds = MihoyoDsSigner.k2(body, Instant.now().epochSecond, randomLetters(6))
        val headers = Headers.Builder()
            .add("x-rpc-app_id", PASSPORT_APP_ID)
            .add("x-rpc-client_type", "2")
            .add("x-rpc-game_biz", "bbs_cn")
            .add("x-rpc-device_id", session.deviceId)
            .add("x-rpc-device_fp", session.deviceFp)
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

    private fun exchangeStoken(
        url: String,
        resultKey: String,
        stoken: String,
        mid: String,
        qrSession: MihoyoQrSession,
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
            .add("x-rpc-device_id", qrSession.deviceId)
            .add("x-rpc-device_fp", qrSession.deviceFp)
            .add("x-rpc-device_model", deviceProfile.model)
            .add("x-rpc-device_name", deviceProfile.name)
            .add("x-rpc-sys_version", deviceProfile.systemVersion)
            .add("DS", ds)
            .add("Cookie", "mid=$mid;stoken=$stoken")
            .add("x-rpc-aigis", "")
            .build()
        val request = Request.Builder().url("$url?$query").headers(headers).get().build()
        val response = executeJson(request)
        ensureSuccess(response, "stoken 换 $resultKey 失败")
        return response["data"]?.jsonObject?.get(resultKey)?.jsonPrimitive?.content.orEmpty()
    }

    private fun passportQrHeaders(deviceId: String, deviceFp: String, exactBody: String): Headers {
        val ds = MihoyoDsSigner.x4(
            query = "",
            body = exactBody,
            epochSeconds = Instant.now().epochSecond,
            randomNumber = random.nextInt(100_001) + 100_000,
        )
        return Headers.Builder()
            .add("User-Agent", deviceProfile.userAgent(PASSPORT_APP_VERSION))
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

    private fun postJson(url: String, body: String, headers: Headers = Headers.Builder().build()): JsonObject {
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

        private const val GAME_QR_FETCH_URL = "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/fetch"
        private const val GAME_QR_QUERY_URL = "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/query"
        private const val GET_TOKEN_BY_GAME_TOKEN_URL =
            "https://api-takumi.mihoyo.com/account/ma-cn-session/app/getTokenByGameToken"
        private const val GAME_QR_APP_ID = "7"

        private const val PASSPORT_QR_FETCH_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin"
        private const val PASSPORT_QR_QUERY_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-passport/app/queryQRLoginStatus"
        private const val GET_COOKIE_TOKEN_URL =
            "https://passport-api.mihoyo.com/account/auth/api/getCookieAccountInfoBySToken"
        private const val PASSPORT_APP_ID = "bll8iq97cem8"
        private const val PASSPORT_APP_VERSION = "2.90.1"
        private const val BBS_VERSION = "2.106.2"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
