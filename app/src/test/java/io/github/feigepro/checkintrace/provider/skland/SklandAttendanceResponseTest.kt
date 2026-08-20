package io.github.feigepro.checkintrace.provider.skland

import io.github.feigepro.checkintrace.data.CheckInResult
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SklandAttendanceResponseTest {
    @Test
    fun zeroCodeIsSuccess() {
        val result = parse("""{"code":0,"data":{}}""")

        assertTrue(result is CheckInResult.Success)
    }

    @Test
    fun zeroStatusIsAlsoSuccess() {
        val result = parse("""{"status":0,"data":{}}""")

        assertTrue(result is CheckInResult.Success)
    }

    @Test
    fun successWithoutDataIsProtocolError() {
        val result = parse("""{"code":0}""") as CheckInResult.Failure

        assertEquals("PROTOCOL_ERROR", result.code)
    }

    @Test
    fun duplicateCodeIsAlreadyCheckedInEvenWithoutMessage() {
        val result = parse("""{"code":10001,"message":""}""")

        assertEquals(CheckInResult.AlreadyCheckedIn, result)
    }

    @Test
    fun duplicateMessageIsAlreadyCheckedIn() {
        val result = parse("""{"code":10001,"message":"请勿重复签到"}""")

        assertEquals(CheckInResult.AlreadyCheckedIn, result)
    }

    @Test
    fun missingCodeIsProtocolError() {
        val result = parse("""{"message":"响应字段缺失"}""") as CheckInResult.Failure

        assertEquals("PROTOCOL_ERROR", result.code)
    }

    @Test
    fun codeTakesPriorityWhenBothCodesArePresent() {
        val result = parse("""{"status":0,"code":10002,"message":"凭证失效"}""") as CheckInResult.Failure

        assertEquals("AUTH_REQUIRED", result.code)
    }

    @Test
    fun connectionFailureCanRetryBecauseRequestDidNotReachServer() {
        assertTrue(SklandCheckInFailurePolicy.isSafeToRetry(UnknownHostException("offline")))
    }

    @Test
    fun responseTimeoutDoesNotRetryBecauseCheckInMayAlreadyBeCommitted() {
        assertFalse(SklandCheckInFailurePolicy.isSafeToRetry(SocketTimeoutException("timeout")))
    }

    private fun parse(raw: String): CheckInResult =
        SklandAttendanceResponse.parse(Json.parseToJsonElement(raw).jsonObject)
}
