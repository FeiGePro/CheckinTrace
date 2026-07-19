package io.github.feigepro.checkintrace.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun redactsJsonAndHeaderSecrets() {
        val value = LogRedactor.redact(
            "Authorization: BearerSecret, {\"token\":\"abc123\",\"cred\":\"cred123\"}",
        )

        assertFalse(value.contains("BearerSecret"))
        assertFalse(value.contains("abc123"))
        assertFalse(value.contains("cred123"))
        assertTrue(value.contains("[REDACTED]"))
    }
}
