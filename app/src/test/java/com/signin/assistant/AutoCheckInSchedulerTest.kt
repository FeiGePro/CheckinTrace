package com.signin.assistant

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCheckInSchedulerTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun schedulesLaterTimeOnSameDay() {
        val now = ZonedDateTime.of(2026, 7, 19, 7, 15, 20, 0, zone)
        val next = AutoCheckInScheduler.nextRun(now, DailyCheckInTime(8, 30))
        assertEquals(ZonedDateTime.of(2026, 7, 19, 8, 30, 0, 0, zone), next)
    }

    @Test
    fun schedulesTomorrowWhenSelectedTimeHasPassed() {
        val now = ZonedDateTime.of(2026, 7, 19, 20, 0, 0, 0, zone)
        val next = AutoCheckInScheduler.nextRun(now, DailyCheckInTime(6, 45))
        assertEquals(ZonedDateTime.of(2026, 7, 20, 6, 45, 0, 0, zone), next)
    }
}