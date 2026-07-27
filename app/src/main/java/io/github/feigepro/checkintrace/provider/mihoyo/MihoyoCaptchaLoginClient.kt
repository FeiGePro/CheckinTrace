package io.github.feigepro.checkintrace.provider.mihoyo

import io.github.feigepro.checkintrace.logging.DevLogger
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class MihoyoCaptchaChallenge(
    val actionType: String,
    val countdownSeconds: Int,
)

class MihoyoAigisRequiredException(
    val aigis: String,
    message: String,
) : IllegalStateException(message)

/**
 * 米游社次要登录流程。
 *
 * 完整采用 README 所列 MiyoQian 的 Android 短信验证码流程：RSA 加密手机号、
 * createLoginCaptcha、AIGIS、loginByMobileCaptcha、stoken 换 cookie_token。
 * 成功后再使用与主二维码相同的 deviceLogin/saveDevice 流程登记本机 Android 设备。
 */
class MihoyoCaptchaLoginClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
    private val deviceProfile: MihoyoDeviceProfile = MihoyoDeviceProfile.current(),
    private val deviceIdentity: MihoyoDeviceIdentity = MihoyoQrLoginClient.generateDeviceIdentity(),
    private val deviceRegistrationApi: MihoyoDeviceRegistrationApi = MihoyoDeviceRegistrationApi(),
) {
    suspend fun sendCaptcha(
        phone: String,
        aigis: String = "",
        taskId: String? = null,
    ): Result<MihoyoCaptchaChallenge> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPhone = normalizeCnPhone(phone)
            val body = buildString {
                append("{\"area_code\":")
                append(jsonString(rsaEncrypt("+86")))
                append(",\"mobile\":")
                append(jsonString(rsaEncrypt(normalizedPhone)))
                append('}')
            }
            val (response, responseHeaders) = postJsonWithHeaders(
                LOGIN_CAPTCHA_URL,
                body,
                captchaHeaders(aigis, create = true),
            )
            ensureSuccessOrAigis(response, responseHeaders, "发送短信验证码失败")
            val data = response["data"]?.jsonObject ?: error("发送短信验证码失败：接口未返回 data")
            val actionType = data["action_type"]?.jsonPrimitive?.content.orEmpty()
            check(actionType.isNotBlank()) { "发送短信验证码失败：接口未返回 action_type" }
            val countdown = data["countdown"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 60
            DevLogger.info("米游社/短信登录", "短信验证码已发送", taskId)
            MihoyoCaptchaChallenge(actionType, countdown)
        }.onFailure {
            DevLogger.error("米游社/短信登录", it.message ?: "发送短信验证码失败", taskId)
        }
    }

    suspend fun loginByCaptcha(
        phone: String,
        captcha: String,
        actionType: String,
        aigis: String = "",
        taskId: String? = null,
    ): Result<MihoyoCredentialBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPhone = normalizeCnPhone(phone)
            val normalizedCaptcha = captcha.trim()
            require(normalizedCaptcha.matches(Regex("\\d{4,8}"))) { "请输入正确的短信验证码" }
            require(actionType.isNotBlank()) { "请先发送短信验证码" }

            val body = buildString {
                append("{\"area_code\":")
                append(jsonString(rsaEncrypt("+86")))
                append(",\"mobile\":")
                append(jsonString(rsaEncrypt(normalizedPhone)))
                append(",\"action_type\":")
                append(jsonString(actionType))
                append(",\"captcha\":")
                append(jsonString(normalizedCaptcha))
                append('}')
            }
            val (response, responseHeaders) = postJsonWithHeaders(
                LOGIN_BY_MOBILE_CAPTCHA_URL,
                body,
                captchaHeaders(aigis, create = false),
            )
            ensureSuccessOrAigis(response, responseHeaders, "短信验证码登录失败")

            val data = response["data"]?.jsonObject ?: error("短信验证码登录结果缺少 data")
            val userInfo = data["user_info"]?.jsonObject ?: error("短信验证码登录结果缺少 user_info")
            val tokenInfo = data["token"]?.jsonObject ?: error("短信验证码登录结果缺少 token")
            val stoken = tokenInfo["token"]?.jsonPrimitive?.content.orEmpty()
            val mid = userInfo["mid"]?.jsonPrimitive?.content.orEmpty()
            val accountId = userInfo["aid"]?.jsonPrimitive?.content.orEmpty()
            check(stoken.isNotBlank() && mid.isNotBlank() && accountId.isNotBlank()) {
                "短信验证码登录结果缺少 stoken/mid/aid"
            }

            val cookieToken = exchangeCookieToken(stoken, mid)
            check(cookieToken.isNotBlank()) { "stoken 换 cookie_token 失败：接口未返回 token" }
            val credential = MihoyoCredentialBundle(
                accountId = accountId,
                mid = mid,
                stoken = stoken,
                ltoken = null,
                cookieToken = cookieToken,
                deviceId = deviceIdentity.deviceId,
                deviceFp = deviceIdentity.deviceFp,
                deviceModel = deviceProfile.model,
                deviceName = deviceProfile.name,
                systemVersion = deviceProfile.systemVersion,
            )
            deviceRegistrationApi.register(credential, taskId).getOrThrow()
            DevLogger.info("米游社/短信登录", "短信登录及 Android 设备注册成功", taskId)
            credential
        }.onFailure {
            DevLogger.error("米游社/短信登录", it.message ?: "短信验证码登录失败", taskId)
        }
    }

    private fun exchangeCookieToken(stoken: String, mid: String): String {
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
            .add("x-rpc-device_id", deviceIdentity.deviceId)
            .add("x-rpc-device_fp", deviceIdentity.deviceFp)
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
        val (response, _) = executeJsonWithHeaders(request)
        ensureSuccess(response, "stoken 换 cookie_token 失败")
        return response["data"]?.jsonObject?.get("cookie_token")?.jsonPrimitive?.content.orEmpty()
    }

    private fun captchaHeaders(aigis: String, create: Boolean): Headers {
        val builder = Headers.Builder()
            .add("x-rpc-aigis", aigis)
            .add("x-rpc-app_version", BBS_VERSION)
            .add("x-rpc-client_type", "2")
            .add("x-rpc-app_id", PASSPORT_APP_ID)
            .add("x-rpc-device_fp", deviceIdentity.deviceFp)
            .add("x-rpc-device_name", deviceProfile.name)
            .add("x-rpc-device_id", deviceIdentity.deviceId)
            .add("x-rpc-device_model", deviceProfile.model)
            .add("User-Agent", "Mozilla/5.0 (Linux; Android ${deviceProfile.systemVersion}) Mobile miHoYoBBS/$BBS_VERSION")
            .add("Content-Type", "application/json")
        if (create) {
            builder
                .add("Referer", "https://user.miyoushe.com/")
                .add("x-rpc-game_biz", "hk4e_cn")
        }
        return builder.build()
    }

    private fun postJsonWithHeaders(url: String, body: String, headers: Headers): Pair<JsonObject, Headers> {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJsonWithHeaders(request)
    }

    private fun executeJsonWithHeaders(request: Request): Pair<JsonObject, Headers> =
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val responseHeaders = response.headers
            check(response.isSuccessful) { "HTTP ${response.code}" }
            check(body.isNotBlank()) { "接口返回空内容" }
            json.parseToJsonElement(body).jsonObject to responseHeaders
        }

    private fun ensureSuccessOrAigis(value: JsonObject, headers: Headers, prefix: String) {
        if (retcode(value) == 0) return
        val aigis = headers["x-rpc-aigis"].orEmpty()
        if (aigis.isNotBlank()) {
            throw MihoyoAigisRequiredException(
                aigis,
                "$prefix：需要完成米游社官方安全验证；retcode=${retcode(value)} message=${message(value)}",
            )
        }
        ensureSuccess(value, prefix)
    }

    private fun ensureSuccess(value: JsonObject, prefix: String) {
        val code = retcode(value)
        check(code == 0) { "$prefix: retcode=$code message=${message(value)}" }
    }

    private fun rsaEncrypt(value: String): String {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(RSA_MODULUS, 16), BigInteger.valueOf(RSA_EXPONENT)),
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, random)
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun jsonString(value: String): String = json.encodeToString(String.serializer(), value)

    private fun retcode(value: JsonObject): Int =
        value["retcode"]?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MIN_VALUE

    private fun message(value: JsonObject): String =
        value["message"]?.jsonPrimitive?.content ?: "未知接口错误"

    companion object {
        internal fun normalizeCnPhone(value: String): String {
            var digits = value.filter { it.isDigit() }
            if (digits.length == 13 && digits.startsWith("86")) digits = digits.drop(2)
            require(digits.matches(Regex("1[3-9]\\d{9}"))) { "请输入正确的中国大陆手机号" }
            return digits
        }

        private const val LOGIN_CAPTCHA_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-verifier/verifier/createLoginCaptcha"
        private const val LOGIN_BY_MOBILE_CAPTCHA_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-passport/app/loginByMobileCaptcha"
        private const val GET_COOKIE_TOKEN_URL =
            "https://passport-api.mihoyo.com/account/auth/api/getCookieAccountInfoBySToken"
        private const val PASSPORT_APP_ID = "bll8iq97cem8"
        private const val BBS_VERSION = "2.106.2"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val RSA_MODULUS =
            "c3bde91d3cc1cddc06219bfbe4b494fe609afb708e4372c34aa9db31e43657d200" +
                "ee585b888f377006eb6b2183cd9912751bcc9b0c817ba035b6784a66e6c31b2fd" +
                "cecf44c5709dbeaae7e75a842dbaa3d17c6d3132296821c5488e743df3e94c557" +
                "d5edfe19b2570a24a0e5c59401200a7f900a01ace766c5a1832dca2fb111"
        private const val RSA_EXPONENT = 65537L

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
