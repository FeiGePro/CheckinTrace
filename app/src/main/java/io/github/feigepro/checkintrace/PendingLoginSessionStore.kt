package io.github.feigepro.checkintrace

import android.content.Context
import io.github.feigepro.checkintrace.provider.skland.SklandQrSession

internal data class PendingSklandQrSession(
    val session: SklandQrSession,
    val createdAtEpochMillis: Long,
)

/**
 * Stores only short-lived QR session metadata. Account credentials remain in the encrypted credential store.
 * This allows the same QR code and scanId to survive activity/process recreation while the user opens the
 * screenshot preview, gallery, or the community app.
 */
internal class PendingLoginSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun saveSkland(
        session: SklandQrSession,
        createdAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        preferences.edit()
            .putString(KEY_SKLAND_SCAN_ID, session.scanId)
            .putString(KEY_SKLAND_QR_CONTENT, session.qrContent)
            .putLong(KEY_SKLAND_CREATED_AT, createdAtEpochMillis)
            .commit()
    }

    fun loadSkland(nowEpochMillis: Long = System.currentTimeMillis()): PendingSklandQrSession? {
        val scanId = preferences.getString(KEY_SKLAND_SCAN_ID, null).orEmpty()
        val qrContent = preferences.getString(KEY_SKLAND_QR_CONTENT, null).orEmpty()
        val createdAt = preferences.getLong(KEY_SKLAND_CREATED_AT, 0L)
        val age = nowEpochMillis - createdAt
        if (
            scanId.isBlank() ||
            qrContent.isBlank() ||
            createdAt <= 0L ||
            age < 0L ||
            age >= SKLAND_QR_TTL_MILLIS
        ) {
            clearSkland()
            return null
        }
        return PendingSklandQrSession(
            session = SklandQrSession(scanId = scanId, qrContent = qrContent),
            createdAtEpochMillis = createdAt,
        )
    }

    fun clearSkland() {
        preferences.edit()
            .remove(KEY_SKLAND_SCAN_ID)
            .remove(KEY_SKLAND_QR_CONTENT)
            .remove(KEY_SKLAND_CREATED_AT)
            .commit()
    }

    companion object {
        internal const val SKLAND_QR_TTL_MILLIS = 5 * 60 * 1_000L

        private const val PREFERENCES_NAME = "pending_login_sessions"
        private const val KEY_SKLAND_SCAN_ID = "skland_scan_id"
        private const val KEY_SKLAND_QR_CONTENT = "skland_qr_content"
        private const val KEY_SKLAND_CREATED_AT = "skland_created_at"
    }
}
