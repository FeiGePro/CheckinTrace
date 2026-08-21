package io.github.feigepro.checkintrace.provider.skland

import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SklandApiTest {
    @Test
    fun bindingQueryRetriesSocketClosedWithFreshRequest() = runTest {
        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (attempts.getAndIncrement() == 0) {
                throw SocketException("Socket closed")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"code":0,"data":{"list":[]}}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()

        val result = SklandApi(client = client).getBindings(SklandSession("cred", "sign-token"))

        assertTrue(result.isSuccess)
        assertEquals(2, attempts.get())
    }
}
