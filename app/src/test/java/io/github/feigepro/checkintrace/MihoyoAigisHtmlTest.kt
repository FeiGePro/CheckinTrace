package io.github.feigepro.checkintrace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihoyoAigisHtmlTest {
    @Test
    fun `security challenge mounts both geetest versions into visible container`() {
        val html = buildMihoyoAigisHtml(
            """{"session_id":"session","data":{"gt":"captcha","risk_type":"slide"}}""",
        )

        assertTrue(html.contains("product: \"custom\""))
        assertTrue(html.contains("area: \"#box\""))
        assertTrue(html.contains("captcha.appendTo(\"#box\")"))
        assertTrue(html.contains("captcha.showCaptcha()"))
        assertTrue(html.contains("AndroidBridge.solved"))
        assertFalse(html.contains("captcha.verify()"))
    }
}
