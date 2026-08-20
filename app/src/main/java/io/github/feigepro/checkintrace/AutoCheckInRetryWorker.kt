package io.github.feigepro.checkintrace

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Re-enqueues the real task after a manual/automatic execution lock clears. */
class AutoCheckInRetryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AutoCheckInScheduler.enqueueWork(applicationContext)
        return Result.success()
    }
}
