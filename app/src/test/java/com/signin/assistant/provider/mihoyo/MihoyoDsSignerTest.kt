package com.signin.assistant.provider.mihoyo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MihoyoDsSignerTest {
    @Test
    fun x4IsDeterministicForFixedInputs() {
        val value = MihoyoDsSigner.x4(
            query = "stoken=test",
            epochSeconds = 1_700_000_000,
            randomNumber = 123456,
        )
        assertEquals("1700000000,123456,6e8ae81dc1bb07278dbc274acca81946", value)
    }

    @Test
    fun k2BodyAffectsSignature() {
        val first = MihoyoDsSigner.k2("{}", 1_700_000_000, "AbCdEf")
        val second = MihoyoDsSigner.k2("{\"a\":1}", 1_700_000_000, "AbCdEf")
        assertNotEquals(first, second)
    }

    @Test
    fun androidSignaturesAreDeterministic() {
        assertEquals(
            MihoyoDsSigner.androidSimple(100, "abc123"),
            MihoyoDsSigner.androidSimple(100, "abc123"),
        )
        assertNotEquals(
            MihoyoDsSigner.androidData("{}", 100, 123456),
            MihoyoDsSigner.androidData("{\"a\":1}", 100, 123456),
        )
    }
}
