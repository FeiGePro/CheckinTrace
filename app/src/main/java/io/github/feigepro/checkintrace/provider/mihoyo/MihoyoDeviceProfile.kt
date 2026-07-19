package io.github.feigepro.checkintrace.provider.mihoyo

import android.os.Build

data class MihoyoDeviceProfile(
    val model: String,
    val name: String,
    val systemVersion: String,
) {
    fun userAgent(appVersion: String): String =
        "Mozilla/5.0 (Linux; Android $systemVersion; $model) Mobile miHoYoBBS/$appVersion"

    companion object {
        fun current(): MihoyoDeviceProfile {
            val model = Build.MODEL?.trim().orEmpty()
                .ifBlank { Build.DEVICE?.trim().orEmpty() }
                .ifBlank { "Android" }
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val name = if (
                manufacturer.isBlank() ||
                model.startsWith(manufacturer, ignoreCase = true)
            ) {
                model
            } else {
                "$manufacturer $model"
            }
            return MihoyoDeviceProfile(
                model = model,
                name = name,
                systemVersion = Build.VERSION.RELEASE?.trim().orEmpty().ifBlank {
                    Build.VERSION.SDK_INT.toString()
                },
            )
        }
    }
}
