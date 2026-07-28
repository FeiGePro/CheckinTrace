package io.github.feigepro.checkintrace

import android.content.Context
import java.time.LocalDate

internal data class DailyCheckInProgress(
    val date: String,
    val completedGameIds: Set<String> = emptySet(),
) {
    fun normalizedFor(today: String): DailyCheckInProgress =
        if (date == today) this else DailyCheckInProgress(date = today)
}

/**
 * Manual and scheduled check-ins share today's definitive game completion state.
 * A new local calendar date automatically starts with an empty record.
 */
internal class DailyCheckInProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadToday(today: LocalDate = LocalDate.now()): DailyCheckInProgress {
        val date = today.toString()
        return DailyCheckInProgress(
            date = preferences.getString(KEY_DATE, null).orEmpty(),
            completedGameIds = preferences.getStringSet(KEY_COMPLETED_GAMES, emptySet()).orEmpty().toSet(),
        ).normalizedFor(date)
    }

    @Synchronized
    fun markGameCompleted(gameId: String) {
        val current = loadToday()
        val updated = current.copy(completedGameIds = current.completedGameIds + gameId)
        check(
            preferences.edit()
                .putString(KEY_DATE, updated.date)
                .putStringSet(KEY_COMPLETED_GAMES, updated.completedGameIds.toSet())
                .commit(),
        ) { "无法保存今日签到完成状态" }
    }

    private companion object {
        const val PREFERENCES_NAME = "daily_checkin_progress"
        const val KEY_DATE = "date"
        const val KEY_COMPLETED_GAMES = "completed_games"
    }
}
