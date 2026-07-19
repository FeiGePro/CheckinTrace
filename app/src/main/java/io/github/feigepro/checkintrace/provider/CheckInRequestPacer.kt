package io.github.feigepro.checkintrace.provider

import kotlinx.coroutines.delay

/** Keeps consecutive attendance pipelines from becoming a burst of requests. */
class CheckInRequestPacer(
    private val minimumIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
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

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 1_500L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
