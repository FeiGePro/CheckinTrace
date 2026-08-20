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
    INTERRUPTED,
}

@Serializable
data class AutoCheckInSnapshot(
    val state: AutoCheckInRunState,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val attempt: Int = 1,
    val lines: List<String> = emptyList(),
    val completedRoleKeys: Set<String> = emptySet(),
)

class AutoCheckInStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): AutoCheckInSnapshot? = preferences.getString(SNAPSHOT_KEY, null)?.let { encoded ->
        runCatching { json.decodeFromString<AutoCheckInSnapshot>(encoded) }
            .getOrNull()
            ?.let(::recoverStaleRunning)
    }

    fun save(snapshot: AutoCheckInSnapshot) {
        check(preferences.edit().putString(SNAPSHOT_KEY, json.encodeToString(snapshot)).commit()) {
            "保存自动签到状态失败"
        }
    }

    private fun recoverStaleRunning(snapshot: AutoCheckInSnapshot): AutoCheckInSnapshot {
        if (snapshot.state != AutoCheckInRunState.RUNNING) return snapshot
        if (System.currentTimeMillis() - snapshot.startedAtEpochMillis <= STALE_RUNNING_AFTER_MILLIS) {
            return snapshot
        }

        val recovered = snapshot.copy(
            state = AutoCheckInRunState.INTERRUPTED,
            finishedAtEpochMillis = System.currentTimeMillis(),
            lines = snapshot.lines + "上一次自动签到未正常结束，已标记为中断；下次任务会复用已记录的完成项",
        )
        // Persist the recovery so every subsequent screen/worker sees the same state.
        preferences.edit().putString(SNAPSHOT_KEY, json.encodeToString(recovered)).commit()
        return recovered
    }

    private companion object {
        const val PREFERENCES_NAME = "auto_checkin_status"
        const val SNAPSHOT_KEY = "latest_snapshot"
        private const val STALE_RUNNING_AFTER_MILLIS = 6 * 60 * 60 * 1000L
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
    requiresAction ->
        AutoCheckInCompletionDecision(AutoCheckInRunState.ACTION_REQUIRED, shouldRetry = false)

    hasRetryableFailure && runAttemptCount < MAX_RETRY_ATTEMPTS ->
        AutoCheckInCompletionDecision(AutoCheckInRunState.RETRY_SCHEDULED, shouldRetry = true)

    hasFailures ->
        AutoCheckInCompletionDecision(AutoCheckInRunState.FAILED, shouldRetry = false)

    else ->
        AutoCheckInCompletionDecision(AutoCheckInRunState.SUCCESS, shouldRetry = false)
}

private const val MAX_RETRY_ATTEMPTS = 1
