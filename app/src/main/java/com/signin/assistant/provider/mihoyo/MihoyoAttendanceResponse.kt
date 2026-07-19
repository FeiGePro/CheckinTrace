package com.signin.assistant.provider.mihoyo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class MihoyoAttendanceStatus(
    val isSigned: Boolean,
    val firstBind: Boolean,
)

internal object MihoyoAttendanceResponse {
    fun status(response: JsonObject): MihoyoAttendanceStatus {
        val data = response["data"] as? JsonObject
        return MihoyoAttendanceStatus(
            isSigned = data?.get("is_sign")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            firstBind = data?.get("first_bind")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
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
}