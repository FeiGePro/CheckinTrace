package io.github.feigepro.checkintrace.provider.mihoyo

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.GameRole
import io.github.feigepro.checkintrace.logging.DevLogger
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

data class MihoyoGameConfig(
    val appCode: String,
    val activityId: String,
    val infoUrl: String = "https://api-takumi.mihoyo.com/event/luna/info",
    val signUrl: String = "https://api-takumi.mihoyo.com/event/luna/sign",
    val signGame: String? = null,
)

object MihoyoGameConfigs {
    val values = listOf(
        MihoyoGameConfig("hk4e_cn", "e202311201442471", signGame = "hk4e"),
        MihoyoGameConfig("bh3_cn", "e202306201626331"),
        MihoyoGameConfig("bh2_cn", "e202203291431091"),
        MihoyoGameConfig("nxx_cn", "e202202251749321"),
        MihoyoGameConfig("hkrpg_cn", "e202304121516551"),
        MihoyoGameConfig(
            "nap_cn",
            "e202406242138391",
            infoUrl = "https://act-nap-api.mihoyo.com/event/luna/zzz/info",
            signUrl = "https://act-nap-api.mihoyo.com/event/luna/zzz/sign",
            signGame = "zzz",
        ),
    ).associateBy { it.appCode }
}

class MihoyoAttendanceApi(
    private val credential: MihoyoCredentialBundle,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
    private val deviceProfile: MihoyoDeviceProfile = MihoyoDeviceProfile.current(),
) {
    suspend fun getRoles(game: GameDefinition, taskId: String? = null): Result<List<GameRole>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = ROLE_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("game_biz", game.appCode)
                    .build()
                val request = Request.Builder().url(url).headers(baseHeaders()).get().build()
                val response = executeJson(request)
                ensureSuccess(response)
                val list = response["data"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
                list.map { element ->
                    val role = element.jsonObject
                    GameRole(
                        gameId = game.id,
                        uid = role.string("game_uid") ?: error("角色缺少 UID"),
                        nickname = role.string("nickname") ?: "未知角色",
                        channelName = role.string("region_name"),
                        extra = mapOf("region" to (role.string("region") ?: error("角色缺少 region"))),
                    )
                }.also { DevLogger.info("米游社/角色", "${game.displayName} 查询到 ${it.size} 个角色", taskId) }
            }.onFailure { DevLogger.error("米游社/角色", it.message ?: "角色查询失败", taskId) }
        }

    suspend fun getStatus(
        game: GameDefinition,
        role: GameRole,
        taskId: String? = null,
    ): Result<MihoyoAttendanceStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val config = requireConfig(game)
            val url = config.infoUrl.toHttpUrl().newBuilder()
                .addQueryParameter("lang", "zh-cn")
                .addQueryParameter("act_id", config.activityId)
                .addQueryParameter("region", role.extra["region"] ?: error("角色缺少 region"))
                .addQueryParameter("uid", role.uid)
                .build()
            val request = Request.Builder()
                .url(url)
                .headers(signedHeaders(config, null))
                .get()
                .build()
            val response = executeJson(request)
            ensureSuccess(response)
            val status = MihoyoAttendanceResponse.status(response)
            val message = when {
                status.firstBind -> "首次绑定，需先手动签到一次"
                status.isSigned -> "今日已签到"
                else -> "今日未签到"
            }
            DevLogger.info("米游社/${game.displayName}", message, taskId)
            status
        }.onFailure { DevLogger.error("米游社/${game.displayName}", it.message ?: "状态查询失败", taskId) }
    }

    suspend fun checkIn(
        game: GameDefinition,
        role: GameRole,
        taskId: String? = null,
    ): CheckInResult = withContext(Dispatchers.IO) {
        val status = getStatus(game, role, taskId).getOrElse { error ->
            DevLogger.error("米游社/${game.displayName}", error.message ?: "状态查询失败", taskId)
            return@withContext CheckInResult.Failure(
                code = "STATUS_QUERY_FAILED",
                message = error.message ?: "状态查询失败",
                retryable = MihoyoCheckInFailurePolicy.isSafeToRetryBeforeSubmit(error),
            )
        }
        if (status.firstBind) {
            return@withContext CheckInResult.Failure("FIRST_BIND_REQUIRED", "首次绑定，请先在米游社手动签到一次")
        }
        if (status.isSigned) return@withContext CheckInResult.AlreadyCheckedIn

        val config = requireConfig(game)
        val region = role.extra["region"] ?: return@withContext CheckInResult.Failure("ROLE_INVALID", "角色缺少 region")
        val body = "{\"act_id\":\"${config.activityId}\",\"region\":\"${escape(region)}\",\"uid\":\"${escape(role.uid)}\"}"
        val request = Request.Builder()
            .url(config.signUrl)
            .headers(signedHeaders(config, body))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching { executeJson(request) }.fold(
            onSuccess = { response ->
                if (MihoyoAttendanceResponse.retcode(response) == -5003) {
                    CheckInResult.AlreadyCheckedIn
                } else {
                    runCatching { ensureSuccess(response) }.fold(
                        onSuccess = {
                            if (MihoyoAttendanceResponse.requiresHumanVerification(response)) {
                                DevLogger.warn("米游社/${game.displayName}", "触发平台验证，停止后续请求", taskId)
                                CheckInResult.Failure("CAPTCHA_REQUIRED", "平台要求人工验证")
                            } else {
                                DevLogger.info("米游社/${game.displayName}", "签到成功", taskId)
                                CheckInResult.Success("签到成功")
                            }
                        },
                        onFailure = { error ->
                            DevLogger.error("米游社/${game.displayName}", error.message ?: "签到失败", taskId)
                            CheckInResult.Failure("API_ERROR", error.message ?: "签到失败")
                        },
                    )
                }
            },
            onFailure = { error ->
                DevLogger.error("米游社/${game.displayName}", error.message ?: "签到请求失败", taskId)
                when {
                    MihoyoCheckInFailurePolicy.isAmbiguousAfterSubmit(error) -> CheckInResult.Unknown(
                        "签到请求已发送，但未能读取完整响应；平台可能已经完成签到，本次不自动重试",
                    )
                    else -> CheckInResult.Failure(
                        code = "NETWORK_OR_PROTOCOL",
                        message = error.message ?: "签到请求失败",
                        retryable = MihoyoCheckInFailurePolicy.isSafeToRetryBeforeSubmit(error),
                    )
                }
            },
        )
    }

    private fun signedHeaders(config: MihoyoGameConfig, exactBody: String?): Headers {
        val builder = baseHeaders().newBuilder()
        config.signGame?.let { builder.set("x-rpc-signgame", it) }
        val now = Instant.now().epochSecond
        val ds = if (exactBody == null) {
            MihoyoDsSigner.androidSimple(now, randomLetters(6))
        } else {
            MihoyoDsSigner.androidData(exactBody, now, random.nextInt(100_001) + 100_000)
        }
        return builder.set("DS", ds).build()
    }

    private fun baseHeaders(): Headers = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "zh-CN,en-US;q=0.8")
        .add("Content-Type", "application/json")
        .add("User-Agent", deviceProfile.userAgent(ATTENDANCE_APP_VERSION))
        .add("Referer", "https://act.mihoyo.com/")
        .add("Origin", "https://act.mihoyo.com")
        .add("x-rpc-app_version", ATTENDANCE_APP_VERSION)
        .add("x-rpc-client_type", "2")
        .add("x-rpc-channel", "miyousheluodi")
        .add("X-Requested-With", "com.mihoyo.hyperion")
        .add("x-rpc-device_id", credential.deviceId)
        .add("x-rpc-device_fp", credential.deviceFp)
        .add("x-rpc-device_model", credential.deviceModel?.takeIf { it.isNotBlank() } ?: deviceProfile.model)
        .add("x-rpc-device_name", credential.deviceName?.takeIf { it.isNotBlank() } ?: deviceProfile.name)
        .add("x-rpc-sys_version", credential.systemVersion?.takeIf { it.isNotBlank() } ?: deviceProfile.systemVersion)
        .add("Cookie", credential.cookieHeader())
        .build()

    private fun executeJson(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        check(response.isSuccessful) { "HTTP ${response.code}" }
        check(body.isNotBlank()) { "接口返回空内容" }
        json.parseToJsonElement(body).jsonObject
    }

    private fun ensureSuccess(response: JsonObject) {
        val code = MihoyoAttendanceResponse.retcode(response)
        if (code != 0) error(response.string("message") ?: "接口错误 retcode=$code")
    }

    private fun requireConfig(game: GameDefinition): MihoyoGameConfig =
        MihoyoGameConfigs.values[game.appCode] ?: error("未配置 ${game.displayName} 的签到活动")

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun randomLetters(length: Int): String = buildString(length) {
        val values = "abcdefghijklmnopqrstuvwxyz0123456789"
        repeat(length) { append(values[random.nextInt(values.length)]) }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val ROLE_URL = "https://api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie"
        private const val ATTENDANCE_APP_VERSION = "2.102.1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

internal object MihoyoCheckInFailurePolicy {
    fun isSafeToRetryBeforeSubmit(error: Throwable): Boolean = when (error) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException -> true
        else -> false
    }

    fun isAmbiguousAfterSubmit(error: Throwable): Boolean = when (error) {
        is SocketTimeoutException,
        is EOFException,
        is ProtocolException -> true
        is IOException -> !isSafeToRetryBeforeSubmit(error)
        else -> false
    }
}
