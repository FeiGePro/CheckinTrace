package io.github.feigepro.checkintrace.logging

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DevLogFormatterTest {
    @Test
    fun formatsUtcInstantUsingSelectedLocalZone() {
        val entry = DevLogEntry(
            timestamp = Instant.parse("2026-07-23T02:22:19Z"),
            level = DevLogLevel.INFO,
            scope = "自动任务",
            message = "每日签到开始",
            taskId = null,
        )

        val result = DevLogFormatter.format(entry, ZoneId.of("Asia/Shanghai"))

        assertEquals(
            "2026-07-23 10:22:19  INFO  自动任务  每日签到开始",
            result,
        )
    }
}
