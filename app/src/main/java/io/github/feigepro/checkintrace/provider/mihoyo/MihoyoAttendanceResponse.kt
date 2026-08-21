package io.github.feigepro.checkintrace.provider.mihoyo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

data class MihoyoAttendanceStatus(
    val isSigned: Boolean,
    val firstBind: Boolean,
)

internal object MihoyoAttendanceResponse {
    fun status(response: JsonObject): MihoyoAttendanceStatus {
        // The info endpoint has returned a smaller data object for some
        // activities and account states. These fields are optional in the
        // wire response; a missing value means "not reported", not a broken
        // login. Keep the historical fallback so the sign request can still
        // confirm the authoritative result from the sign endpoint.
        val data = response["data"] as? JsonObject
        return MihoyoAttendanceStatus(
            isSigned = data.booleanOrNull("is_sign")
                ?: response.booleanOrNull("is_sign")
                ?: false,
            firstBind = data.booleanOrNull("first_bind")
                ?: response.booleanOrNull("first_bind")
                ?: false,
        )
    }

    fun retcode(response: JsonObject): Int =
        response["retcode"]?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MIN_VALUE

    fun requiresHumanVerification(response: JsonObject): Boolean {
        val data = response["data"] as? JsonObject ?: return false
        val success = data["success"]?.jsonPrimitive?.content?.toIntOrNull()
        val riskCode = data["risk_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return success == 1 || riskCode != 0
    }

    private fun JsonObject?.booleanOrNull(key: String): Boolean? {
        val value = this?.get(key) as? JsonPrimitive ?: return null
        return when (value.content.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
}
