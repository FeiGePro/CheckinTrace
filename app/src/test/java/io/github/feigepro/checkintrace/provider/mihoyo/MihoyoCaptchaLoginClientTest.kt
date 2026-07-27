package io.github.feigepro.checkintrace.provider.mihoyo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MihoyoCaptchaLoginClientTest {
    @Test
    fun `normalizes mainland phone formats`() {
        assertEquals("13800138000", MihoyoCaptchaLoginClient.normalizeCnPhone("138 0013 8000"))
        assertEquals("13800138000", MihoyoCaptchaLoginClient.normalizeCnPhone("+86 138-0013-8000"))
    }

    @Test
    fun `rejects invalid phone`() {
        assertThrows(IllegalArgumentException::class.java) {
            MihoyoCaptchaLoginClient.normalizeCnPhone("12345")
        }
    }
}
