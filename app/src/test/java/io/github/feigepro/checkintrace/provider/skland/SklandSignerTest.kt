package io.github.feigepro.checkintrace.provider.skland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SklandSignerTest {
    @Test
    fun signingIsDeterministic() {
        val first = SklandSigner.sign(
            path = "/api/v1/game/attendance",
            bodyOrQuery = "{\"uid\":\"123\",\"gameId\":1}",
            signToken = "test-sign-token",
            epochSeconds = 1_700_000_000,
        )
        val second = SklandSigner.sign(
            path = "/api/v1/game/attendance",
            bodyOrQuery = "{\"uid\":\"123\",\"gameId\":1}",
            signToken = "test-sign-token",
            epochSeconds = 1_700_000_000,
        )

        assertEquals(first, second)
        assertEquals("1699999999", first.timestamp)
        assertEquals("4f297728549f5425e739e80f71a384f5", first.sign)
    }

    @Test
    fun requestBodyChangesSignature() {
        val first = SklandSigner.sign("/path", "{}", "token", 100)
        val second = SklandSigner.sign("/path", "{\"x\":1}", "token", 100)

        assertNotEquals(first.sign, second.sign)
    }
}
