package io.github.feigepro.checkintrace.provider.mihoyo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.feigepro.checkintrace.provider.ProviderFailureException

data class MihoyoAttendanceStatus(
    val isSigned: Boolean,
    val firstBind: Boolean,
)

internal object MihoyoAttendanceResponse {
    fun status(response: JsonObject): MihoyoAttendanceStatus {
        val data = response["data"] as? JsonObject
            ?: throw ProviderFailureException("PROTOCOL_ERROR", "签到状态响应缺少 data")
        if (!data.containsKey("is_sign") || !data.containsKey("first_bind")) {
            throw ProviderFailureException("PROTOCOL_ERROR", "签到状态响应缺少 is_sign/first_bind")
        }
        return MihoyoAttendanceStatus(
            isSigned = data.boolean("is_sign"),
            firstBind = data.boolean("first_bind"),
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

    fun validateSignPayload(response: JsonObject) {
        val data = response["data"] as? JsonObject
            ?: throw ProviderFailureException("PROTOCOL_ERROR", "签到响应缺少 data")
        if (
            !data.containsKey("success") &&
            !data.containsKey("risk_code") &&
            !data.containsKey("is_risk")
        ) {
            throw ProviderFailureException("PROTOCOL_ERROR", "签到响应缺少结果字段")
        }
    }

    private fun JsonObject?.boolean(key: String): Boolean {
        val value = this?.get(key)?.jsonPrimitive?.content ?: return false
        return value.equals("true", ignoreCase = true) || value == "1"
    }
}
