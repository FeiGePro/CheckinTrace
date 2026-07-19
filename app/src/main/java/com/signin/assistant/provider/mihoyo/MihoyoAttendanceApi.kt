package com.signin.assistant.provider.mihoyo

import com.signin.assistant.data.CheckInResult
import com.signin.assistant.data.GameDefinition
import com.signin.assistant.data.GameRole
import com.signin.assistant.logging.DevLogger
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
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit

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
        runCatching {
            val status = getStatus(game, role, taskId).getOrThrow()
            if (status.firstBind) {
                return@runCatching CheckInResult.Failure("FIRST_BIND_REQUIRED", "首次绑定，请先在米游社手动签到一次")
            }
            if (status.isSigned) return@runCatching CheckInResult.AlreadyCheckedIn
            val config = requireConfig(game)
            val region = role.extra["region"] ?: error("角色缺少 region")
            val body = "{\"act_id\":\"${config.activityId}\",\"region\":\"${escape(region)}\",\"uid\":\"${escape(role.uid)}\"}"
            val request = Request.Builder()
                .url(config.signUrl)
                .headers(signedHeaders(config, body))
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = executeJson(request)
            if (MihoyoAttendanceResponse.retcode(response) == -5003) {
                return@runCatching CheckInResult.AlreadyCheckedIn
            }
            ensureSuccess(response)
            if (MihoyoAttendanceResponse.requiresHumanVerification(response)) {
                DevLogger.warn("米游社/${game.displayName}", "触发平台验证，停止后续请求", taskId)
                CheckInResult.Failure("CAPTCHA_REQUIRED", "平台要求人工验证")
            } else {
                DevLogger.info("米游社/${game.displayName}", "签到成功", taskId)
                CheckInResult.Success("签到成功")
            }
        }.getOrElse { error ->
            DevLogger.error("米游社/${game.displayName}", error.message ?: "签到失败", taskId)
            CheckInResult.Failure("NETWORK_OR_PROTOCOL", error.message ?: "签到失败", true)
        }
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

