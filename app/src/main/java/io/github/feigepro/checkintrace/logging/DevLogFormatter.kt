package io.github.feigepro.checkintrace.logging

import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object DevLogFormatter {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun format(entry: DevLogEntry, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val localTimestamp = timestampFormatter.withZone(zoneId).format(entry.timestamp)
        return "$localTimestamp  ${entry.level}  ${entry.scope}  ${entry.message}"
    }
}
