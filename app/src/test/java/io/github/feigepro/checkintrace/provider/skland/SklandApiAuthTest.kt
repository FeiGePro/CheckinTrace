package io.github.feigepro.checkintrace.provider.skland

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SklandApiAuthTest {
    @Test
    fun http401IsMappedToRecoverableAuthenticationFailure() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body(
                        """{"code":10000,"message":"签名凭证已过期"}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()
        val api = SklandApi(client = client)

        val result = api.getBindings(SklandSession(cred = "cred", signToken = "expired-token"))
        val error = result.exceptionOrNull()

        assertTrue(error is SklandAuthException)
        assertEquals(401, (error as SklandAuthException).apiCode)
        assertEquals("签名凭证已过期", error.message)
    }
}
