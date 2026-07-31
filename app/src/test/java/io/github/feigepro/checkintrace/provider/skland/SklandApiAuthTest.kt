package io.github.feigepro.checkintrace.provider.skland

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SklandApiAuthTest {
    @Test
    fun http401IsMappedToRecoverableAuthenticationFailure() = runTest {
        val client = responseClient(401, "Unauthorized", """{"code":10000,"message":"签名凭证已过期"}""")
        val api = SklandApi(client = client)

        val result = api.getBindings(SklandSession(cred = "cred", signToken = "expired-token"))
        val error = result.exceptionOrNull()

        assertTrue(error is SklandAuthException)
        assertEquals(401, (error as SklandAuthException).apiCode)
        assertEquals("签名凭证已过期", error.message)
    }

    @Test
    fun http403StopsInsteadOfEnteringAuthenticationRetryLoop() = runTest {
        val client = responseClient(403, "Forbidden", """{"message":"request forbidden"}""")
        val api = SklandApi(client = client)

        val result = api.getBindings(SklandSession(cred = "cred", signToken = "token"))
        val error = result.exceptionOrNull()

        assertTrue(error is SklandForbiddenException)
        assertEquals("读取绑定角色", (error as SklandForbiddenException).operation)
        assertTrue(error.message.orEmpty().contains("HTTP 403"))
    }

    @Test
    fun endfieldUsesWebEndpointGameRoleHeaderAndEmptyBody() = runTest {
        val captured = AtomicReference<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured.set(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"code":0,"data":{}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = SklandApi(client = client)

        val result = api.checkInEndfield(
            roleId = "role-1",
            serverId = "server-2",
            session = SklandSession(cred = "cred", signToken = "token"),
        )

        assertTrue(result.isSuccess)
        val request = captured.get()
        assertEquals("/web/v1/game/endfield/attendance", request.url.encodedPath)
        assertEquals("3_role-1_server-2", request.header("sk-game-role"))
        assertEquals(SklandApi.SKLAND_USER_AGENT, request.header("User-Agent"))
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        assertEquals("", body)
    }

    private fun responseClient(code: Int, message: String, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(message)
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
}
