package io.github.feigepro.checkintrace.provider.mihoyo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihoyoAttendanceResponseTest {
    @Test
    fun parsesSignedAndFirstBindStatus() {
        val signed = response("""{"retcode":0,"data":{"is_sign":true,"first_bind":false}}""")
        assertEquals(MihoyoAttendanceStatus(isSigned = true, firstBind = false), MihoyoAttendanceResponse.status(signed))

        val firstBind = response("""{"retcode":0,"data":{"is_sign":false,"first_bind":true}}""")
        assertEquals(MihoyoAttendanceStatus(isSigned = false, firstBind = true), MihoyoAttendanceResponse.status(firstBind))
    }

    @Test
    fun numericBooleanFieldsAreAccepted() {
        val status = MihoyoAttendanceResponse.status(
            response("""{"retcode":0,"data":{"is_sign":1,"first_bind":0}}"""),
        )

        assertTrue(status.isSigned)
        assertFalse(status.firstBind)
    }

    @Test
    fun recognizesDuplicateSignRetcode() {
        assertEquals(-5003, MihoyoAttendanceResponse.retcode(response("""{"retcode":-5003}""")))
    }

    @Test
    fun recognizesBothKnownVerificationSignals() {
        assertTrue(MihoyoAttendanceResponse.requiresHumanVerification(response("""{"data":{"success":1}}""")))
        assertTrue(MihoyoAttendanceResponse.requiresHumanVerification(response("""{"data":{"risk_code":375}}""")))
        assertFalse(MihoyoAttendanceResponse.requiresHumanVerification(response("""{"data":{"success":0,"risk_code":0}}""")))
    }

    @Test
    fun missingStatusFieldsRemainCompatibleWithSuccessfulInfoResponses() {
        val status = MihoyoAttendanceResponse.status(response("""{"retcode":0,"data":{}}"""))

        assertFalse(status.isSigned)
        assertFalse(status.firstBind)
    }

    @Test
    fun topLevelStatusFieldsAreAcceptedAsFallback() {
        val status = MihoyoAttendanceResponse.status(
            response("""{"retcode":0,"is_sign":1,"first_bind":0}"""),
        )

        assertTrue(status.isSigned)
        assertFalse(status.firstBind)
    }

    private fun response(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
