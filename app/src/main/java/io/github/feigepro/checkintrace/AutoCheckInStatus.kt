package io.github.feigepro.checkintrace

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class AutoCheckInRunState {
    RUNNING,
    SUCCESS,
    RETRY_SCHEDULED,
    FAILED,
    ACTION_REQUIRED,
}

@Serializable
data class AutoCheckInSnapshot(
    val state: AutoCheckInRunState,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val attempt: Int = 1,
    val lines: List<String> = emptyList(),
)

class AutoCheckInStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): AutoCheckInSnapshot? = preferences.getString(SNAPSHOT_KEY, null)?.let { encoded ->
        runCatching { json.decodeFromString<AutoCheckInSnapshot>(encoded) }.getOrNull()
    }

    fun save(snapshot: AutoCheckInSnapshot) {
        check(preferences.edit().putString(SNAPSHOT_KEY, json.encodeToString(snapshot)).commit()) {
            "保存自动签到状态失败"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "auto_checkin_status"
        const val SNAPSHOT_KEY = "latest_snapshot"
    }
}

internal data class AutoCheckInCompletionDecision(
    val state: AutoCheckInRunState,
    val shouldRetry: Boolean,
)

internal fun decideAutoCheckInCompletion(
    hasFailures: Boolean,
    requiresAction: Boolean,
    hasRetryableFailure: Boolean,
    runAttemptCount: Int,
): AutoCheckInCompletionDecision = when {
    hasRetryableFailure && runAttemptCount < MAX_RETRY_ATTEMPTS ->
        AutoCheckInCompletionDecision(AutoCheckInRunState.RETRY_SCHEDULED, shouldRetry = true)

    requiresAction ->
        AutoCheckInCompletionDecision(AutoCheckInRunState.ACTION_REQUIRED, shouldRetry = false)

    hasFailures ->
        AutoCheckInCompletionDecision(AutoCheckInRunState.FAILED, shouldRetry = false)

    else ->
        AutoCheckInCompletionDecision(AutoCheckInRunState.SUCCESS, shouldRetry = false)
}

private const val MAX_RETRY_ATTEMPTS = 1
