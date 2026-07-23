package io.github.feigepro.checkintrace

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object AutoCheckInNotifier {
    fun notifyFailure(context: Context, snapshot: AutoCheckInSnapshot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "自动签到提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "登录失效、人工验证或自动签到失败时提醒"
            },
        )

        val title = when (snapshot.state) {
            AutoCheckInRunState.ACTION_REQUIRED -> "自动签到需要处理"
            else -> "自动签到未完成"
        }
        val details = snapshot.lines.takeLast(4).joinToString("\n").ifBlank { "请打开签迹查看详情" }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(snapshot.lines.lastOrNull() ?: "请打开签迹查看详情")
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private const val CHANNEL_ID = "auto_checkin_failures"
    private const val NOTIFICATION_ID = 2303
}
