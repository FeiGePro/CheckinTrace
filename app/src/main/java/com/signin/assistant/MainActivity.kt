package com.signin.assistant

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.signin.assistant.data.GameCatalog
import com.signin.assistant.data.GameDefinition
import com.signin.assistant.data.ProviderType
import com.signin.assistant.ui.theme.SignInTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutoCheckInScheduler.ensureScheduled(applicationContext)
        setContent { SignInTheme { MainScreen() } }
    }
}

@Composable
private fun MainScreen(model: MainViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAvailable by rememberSaveable { mutableStateOf(false) }
    val selected = GameCatalog.builtIn.filter { it.id in state.selected }
    val available = GameCatalog.builtIn.filter { it.id !in state.selected }
    val sklandLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(SklandLoginActivity.EXTRA_TOKEN)?.let(model::saveSklandToken)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HeroHeader(
                    selectedCount = selected.size,
                    scheduleLabel = "%02d:%02d".format(state.scheduleHour, state.scheduleMinute),
                    onChangeSchedule = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> model.updateSchedule(hour, minute) },
                            state.scheduleHour,
                            state.scheduleMinute,
                            true,
                        ).show()
                    },
                )
            }
            item {
                AccountPanel(
                    state.mihoyoLoggedIn,
                    state.sklandLoggedIn,
                    !state.busy,
                    model::beginMihoyoLogin,
                ) { sklandLogin.launch(Intent(context, SklandLoginActivity::class.java)) }
            }
            state.qrSession?.let { session ->
                item {
                    val bitmap = remember(session.url) { qrBitmap(session.url) }
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(state.qrStatus.orEmpty(), fontWeight = FontWeight.SemiBold)
                            Image(bitmap.asImageBitmap(), "米游社登录二维码", Modifier.size(216.dp))
                            Text(
                                "截图后可在米游社扫一扫中从相册识别",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = model::cancelMihoyoLogin) { Text("取消登录") }
                        }
                    }
                }
            }
            if (state.qrSession == null && state.qrStatus != null) {
                item { StatusMessage(state.qrStatus.orEmpty()) }
            }
            item {
                GamePanel(
                    selected,
                    available,
                    showAvailable,
                    { showAvailable = !showAvailable },
                    { model.toggleGame(it.id, false) },
                    { model.toggleGame(it.id, true) },
                )
            }
            item {
                Button(
                    onClick = model::runSelectedCheckIns,
                    enabled = !state.busy && state.selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                    }
                    Text(if (state.busy) "正在签到……" else "立即签到", fontWeight = FontWeight.SemiBold)
                }
            }
            if (state.output.isNotEmpty()) item { ResultPanel(state.output) }
            if (BuildConfig.DEBUG) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("开发日志", fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = model::refreshLogs) { Text("刷新并显示") }
                    }
                }
            }
            item {
                Text(
                    "凭证仅加密保存在本机 · 遇到平台验证会立即停止",
                    modifier = Modifier.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun HeroHeader(
    selectedCount: Int,
    scheduleLabel: String,
    onChangeSchedule: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "签迹",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "已选择 $selectedCount 个游戏 · 每天约 $scheduleLabel 自动执行",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
                TextButton(onClick = onChangeSchedule) { Text("修改") }
            }
        }
    }
}

@Composable
private fun AccountPanel(
    mihoyoLoggedIn: Boolean,
    sklandLoggedIn: Boolean,
    enabled: Boolean,
    onMihoyoLogin: () -> Unit,
    onSklandLogin: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column {
            AccountRow("米游社", mihoyoLoggedIn, enabled, onMihoyoLogin)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            AccountRow("森空岛", sklandLoggedIn, enabled, onSklandLogin)
        }
    }
}

@Composable
private fun AccountRow(title: String, loggedIn: Boolean, enabled: Boolean, onLogin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = RoundedCornerShape(50),
                    color = if (loggedIn) Color(0xFF28A66A) else MaterialTheme.colorScheme.outline,
                ) {}
                Text(
                    if (loggedIn) "已连接" else "尚未登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = onLogin,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(if (loggedIn) "重新登录" else "登录") }
    }
}

@Composable
private fun GamePanel(
    selected: List<GameDefinition>,
    available: List<GameDefinition>,
    showAvailable: Boolean,
    onToggleAvailable: () -> Unit,
    onRemove: (GameDefinition) -> Unit,
    onAdd: (GameDefinition) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("我的签到", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "点击已选游戏可移除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CountBadge(selected.size)
            }
            if (selected.isEmpty()) {
                Text("还没有选择游戏，点击下方添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ProviderType.entries.forEach { provider ->
                    val games = selected.filter { it.provider == provider }
                    if (games.isNotEmpty()) {
                        Text(
                            providerTitle(provider),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        GameGrid(games, true, onRemove)
                    }
                }
            }
            if (available.isNotEmpty()) {
                HorizontalDivider()
                TextButton(onClick = onToggleAvailable, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAvailable) "收起可选游戏" else "添加游戏（" + available.size + "）")
                }
                if (showAvailable) GameGrid(available, false, onAdd)
            }
        }
    }
}

@Composable
private fun GameGrid(
    games: List<GameDefinition>,
    selected: Boolean,
    onClick: (GameDefinition) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        games.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { game ->
                    if (selected) {
                        Surface(
                            onClick = { onClick(game) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            GameLabel(game, "✓", Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onClick(game) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        ) { GameLabel(game, "+", Modifier) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GameLabel(game: GameDefinition, mark: String, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            gameShortName(game),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Text(mark, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CountBadge(count: Int) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            "$count 项",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusMessage(message: String) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(message, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun ResultPanel(lines: List<String>) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("最近结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            lines.forEachIndexed { index, line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
                if (index != lines.lastIndex) HorizontalDivider()
            }
        }
    }
}

private fun providerTitle(provider: ProviderType) =
    if (provider == ProviderType.MIHOYO) "米游社" else "森空岛"

private fun gameShortName(game: GameDefinition): String = when (game.id) {
    "mihoyo.starrail" -> "星穹铁道"
    "skland.endfield" -> "终末地"
    else -> game.displayName
}

private fun qrBitmap(content: String): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600)
    return Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until 600) for (y in 0 until 600) {
            setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}
