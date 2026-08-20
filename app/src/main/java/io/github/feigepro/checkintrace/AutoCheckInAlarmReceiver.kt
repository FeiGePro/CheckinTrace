package io.github.feigepro.checkintrace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.feigepro.checkintrace.logging.DevLogger

/**
 * Receives the system alarm, immediately schedules the next calendar occurrence,
 * then lets WorkManager wait for network and battery constraints before running.
 */
class AutoCheckInAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoCheckInScheduler.ALARM_ACTION) return

        val appContext = context.applicationContext
        DevLogger.initialize(appContext)
        AutoCheckInScheduler.scheduleNextAlarm(appContext)
        AutoCheckInScheduler.enqueueWork(appContext)
        DevLogger.info("自动任务", "系统定时已触发，已安排下一次执行")
    }
}
