package io.github.feigepro.checkintrace.provider.mihoyo

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.GameRole
import io.github.feigepro.checkintrace.data.ProviderType
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MihoyoAttendanceApiTest {
    @Test
    fun unsignedRoleIsCheckedThenSignedWithAndroidHeadersAndExactBody() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val raw = if (request.url.encodedPath.endsWith("/info")) {
                """{"retcode":0,"message":"OK","data":{"is_sign":false,"first_bind":false}}"""
            } else {
                """{"retcode":0,"message":"OK","data":{"success":0,"risk_code":0}}"""
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(raw.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val credential = MihoyoCredentialBundle(
            accountId = "10001",
            mid = "mid-test",
            stoken = "stoken-test",
            ltoken = null,
            cookieToken = "cookie-test",
            deviceId = "REAL-DEVICE-ID",
            deviceFp = "abcdef1234567",
            deviceModel = "Phone Model",
            deviceName = "Phone Name",
            systemVersion = "15",
        )
        val api = MihoyoAttendanceApi(
            credential = credential,
            client = client,
            deviceProfile = MihoyoDeviceProfile("fallback", "fallback", "0"),
        )
        val game = GameDefinition("mihoyo.genshin", "原神", ProviderType.MIHOYO, "hk4e_cn")
        val role = GameRole(game.id, "123456789", "旅行者", extra = mapOf("region" to "cn_gf01"))

        assertTrue(api.checkIn(game, role) is CheckInResult.Success)
        assertEquals(2, requests.size)
        assertEquals("GET", requests[0].method)
        val sign = requests[1]
        assertEquals("POST", sign.method)
        assertEquals("2", sign.header("x-rpc-client_type"))
        assertEquals("miyousheluodi", sign.header("x-rpc-channel"))
        assertEquals("com.mihoyo.hyperion", sign.header("X-Requested-With"))
        assertEquals("REAL-DEVICE-ID", sign.header("x-rpc-device_id"))
        assertEquals("Phone Model", sign.header("x-rpc-device_model"))
        assertNotNull(sign.header("DS"))
        val buffer = Buffer()
        sign.body!!.writeTo(buffer)
        assertEquals(
            """{"act_id":"e202311201442471","region":"cn_gf01","uid":"123456789"}""",
            buffer.readUtf8(),
        )
    }

    @Test
    fun riskResponseStopsWithoutRetryEvenWhenRetcodeIsNonZero() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val raw = if (request.url.encodedPath.endsWith("/info")) {
                """{"retcode":0,"message":"OK","data":{"is_sign":false,"first_bind":false}}"""
            } else {
                """{"retcode":-1,"message":"风险验证","data":{"success":1,"risk_code":375}}"""
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(raw.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val credential = MihoyoCredentialBundle(
            accountId = "10001",
            mid = "mid-test",
            stoken = "stoken-test",
            ltoken = null,
            cookieToken = "cookie-test",
            deviceId = "REAL-DEVICE-ID",
            deviceFp = "abcdef1234567",
        )
        val api = MihoyoAttendanceApi(
            credential = credential,
            client = client,
            deviceProfile = MihoyoDeviceProfile("fallback", "fallback", "0"),
        )
        val game = GameDefinition("mihoyo.genshin", "原神", ProviderType.MIHOYO, "hk4e_cn")
        val role = GameRole(game.id, "123456789", "旅行者", extra = mapOf("region" to "cn_gf01"))

        val result = api.checkIn(game, role) as CheckInResult.Failure

        assertEquals("CAPTCHA_REQUIRED", result.code)
        assertTrue(!result.retryable)
    }
}
