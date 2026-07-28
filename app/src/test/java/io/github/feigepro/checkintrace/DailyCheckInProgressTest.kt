package io.github.feigepro.checkintrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyCheckInProgressTest {
    @Test
    fun `same date keeps completed games`() {
        val progress = DailyCheckInProgress(
            date = "2026-07-28",
            completedGameIds = setOf("genshin"),
        )

        assertEquals(progress, progress.normalizedFor("2026-07-28"))
    }

    @Test
    fun `new date clears yesterday completion`() {
        val progress = DailyCheckInProgress(
            date = "2026-07-27",
            completedGameIds = setOf("genshin"),
        ).normalizedFor("2026-07-28")

        assertEquals("2026-07-28", progress.date)
        assertTrue(progress.completedGameIds.isEmpty())
    }
}
