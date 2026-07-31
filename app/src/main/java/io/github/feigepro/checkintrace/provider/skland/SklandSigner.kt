package io.github.feigepro.checkintrace.provider.skland

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SklandSignedHeaders(
    val sign: String,
    val timestamp: String,
    val platform: String = "",
    val deviceId: String = "",
    val versionName: String = "",
)

/** 签名格式与 README 所列 skyland_auto_checkin 保持一致。 */
object SklandSigner {
    fun sign(
        path: String,
        bodyOrQuery: String,
        signToken: String,
        epochSeconds: Long,
    ): SklandSignedHeaders {
        val timestamp = (epochSeconds - 2).toString()
        val headerJson = "{\"platform\":\"\",\"timestamp\":\"$timestamp\",\"dId\":\"\",\"vName\":\"\"}"
        val input = path + bodyOrQuery + timestamp + headerJson
        val hmac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            doFinal(input.toByteArray(Charsets.UTF_8)).toHex()
        }
        val sign = MessageDigest.getInstance("MD5")
            .digest(hmac.toByteArray(Charsets.UTF_8))
            .toHex()
        return SklandSignedHeaders(sign = sign, timestamp = timestamp)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
