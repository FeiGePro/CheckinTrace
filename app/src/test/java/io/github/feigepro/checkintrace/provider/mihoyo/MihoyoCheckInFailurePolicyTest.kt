package io.github.feigepro.checkintrace.provider.mihoyo

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihoyoCheckInFailurePolicyTest {
    @Test
    fun `response timeout is unknown and not retried`() {
        val error = SocketTimeoutException("timeout while reading response")

        assertTrue(MihoyoCheckInFailurePolicy.isAmbiguousAfterSubmit(error))
        assertFalse(MihoyoCheckInFailurePolicy.isSafeToRetryBeforeSubmit(error))
    }

    @Test
    fun `dns failure can be retried before submission`() {
        val error = UnknownHostException("dns unavailable")

        assertTrue(MihoyoCheckInFailurePolicy.isSafeToRetryBeforeSubmit(error))
        assertFalse(MihoyoCheckInFailurePolicy.isAmbiguousAfterSubmit(error))
    }

    @Test
    fun `connection refused can be retried before submission`() {
        assertTrue(MihoyoCheckInFailurePolicy.isSafeToRetryBeforeSubmit(ConnectException("connection refused")))
    }
}
