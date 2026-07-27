package io.github.feigepro.checkintrace.provider.skland

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SklandCheckInFailurePolicyTest {
    @Test
    fun `response timeout is unknown and must not retry`() {
        val error = SocketTimeoutException("timeout while reading response")

        assertTrue(SklandCheckInFailurePolicy.isAmbiguousAfterSubmit(error))
        assertFalse(SklandCheckInFailurePolicy.isSafeToRetry(error))
    }

    @Test
    fun `dns failure is safe to retry and not an unknown submitted result`() {
        val error = UnknownHostException("dns unavailable")

        assertTrue(SklandCheckInFailurePolicy.isSafeToRetry(error))
        assertFalse(SklandCheckInFailurePolicy.isAmbiguousAfterSubmit(error))
    }

    @Test
    fun `connection failure is safe to retry`() {
        assertTrue(SklandCheckInFailurePolicy.isSafeToRetry(ConnectException("connection refused")))
    }
}
