package io.github.feigepro.checkintrace.provider

import kotlinx.coroutines.delay

/** Keeps consecutive attendance pipelines from becoming a burst of requests. */
class CheckInRequestPacer(
    private val minimumIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    init {
        require(minimumIntervalMillis >= 0) { "minimumIntervalMillis must not be negative" }
    }

    private var previousStartNanos: Long? = null

    suspend fun awaitTurn() {
        val previous = previousStartNanos
        if (previous != null) {
            val elapsedMillis = (System.nanoTime() - previous) / NANOS_PER_MILLI
            val remaining = minimumIntervalMillis - elapsedMillis
            if (remaining > 0) delay(remaining)
        }
        previousStartNanos = System.nanoTime()
    }

    internal companion object {
        /** Fixed low-frequency interval; this is pacing, not anti-detection logic. */
        const val DEFAULT_INTERVAL_MILLIS = 30_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
