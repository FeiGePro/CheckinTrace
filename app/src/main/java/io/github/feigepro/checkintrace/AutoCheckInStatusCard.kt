package io.github.feigepro.checkintrace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AutoCheckInStatusCard(
    snapshot: AutoCheckInSnapshot?,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("自动签到状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onRefresh) { Text("刷新") }
            }

            if (snapshot == null) {
                Text(
                    "后台任务已登记，尚无自动执行记录。首次计划执行后可在这里查看结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                runStateLabel(snapshot.state),
                color = runStateColor(snapshot.state),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "开始时间：${formatTimestamp(snapshot.startedAtEpochMillis)} · 第 ${snapshot.attempt} 次尝试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            snapshot.finishedAtEpochMillis?.let {
                Text(
                    "完成时间：${formatTimestamp(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snapshot.lines.takeLast(MAX_VISIBLE_LINES).forEachIndexed { index, line ->
                HorizontalDivider()
                Text(line, style = MaterialTheme.typography.bodySmall)
                if (index == MAX_VISIBLE_LINES - 1 && snapshot.lines.size > MAX_VISIBLE_LINES) {
                    Text(
                        "仅显示最近 $MAX_VISIBLE_LINES 条结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun runStateColor(state: AutoCheckInRunState) = when (state) {
    AutoCheckInRunState.SUCCESS -> MaterialTheme.colorScheme.primary
    AutoCheckInRunState.RUNNING, AutoCheckInRunState.RETRY_SCHEDULED -> MaterialTheme.colorScheme.tertiary
    AutoCheckInRunState.FAILED, AutoCheckInRunState.ACTION_REQUIRED -> MaterialTheme.colorScheme.error
}

private fun runStateLabel(state: AutoCheckInRunState): String = when (state) {
    AutoCheckInRunState.RUNNING -> "正在自动签到"
    AutoCheckInRunState.SUCCESS -> "最近一次自动签到已完成"
    AutoCheckInRunState.RETRY_SCHEDULED -> "遇到临时错误，已安排重试"
    AutoCheckInRunState.FAILED -> "最近一次自动签到存在失败"
    AutoCheckInRunState.ACTION_REQUIRED -> "需要重新登录或完成平台验证"
}

private fun formatTimestamp(epochMillis: Long): String = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

private const val MAX_VISIBLE_LINES = 6
private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
