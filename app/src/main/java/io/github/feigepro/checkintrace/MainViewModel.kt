package io.github.feigepro.checkintrace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameCatalog
import io.github.feigepro.checkintrace.data.ProviderType
import io.github.feigepro.checkintrace.logging.DevLogFormatter
import io.github.feigepro.checkintrace.logging.DevLogger
import io.github.feigepro.checkintrace.provider.CheckInRequestPacer
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoProvider
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoQrLoginClient
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoQrSession
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoQrState
import io.github.feigepro.checkintrace.provider.skland.SklandProvider
import io.github.feigepro.checkintrace.security.CredentialRepository
import io.github.feigepro.checkintrace.security.EncryptedCredentialStore
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val selected: Set<String> = GameCatalog.builtIn.filter { it.enabledByDefault }.mapTo(mutableSetOf()) { it.id },
    val mihoyoLoggedIn: Boolean = false,
    val sklandLoggedIn: Boolean = false,
    val scheduleHour: Int = 8,
    val scheduleMinute: Int = 30,
    val autoCheckInSnapshot: AutoCheckInSnapshot? = null,
    val qrSession: MihoyoQrSession? = null,
    val qrStatus: String? = null,
    val busy: Boolean = false,
    val output: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CredentialRepository(EncryptedCredentialStore(application))
    private val autoCheckInStatusStore = AutoCheckInStatusStore(application)
    private val qrClient = MihoyoQrLoginClient(
        deviceIdentity = repository.loadMihoyoDeviceIdentity()
            ?: MihoyoQrLoginClient.generateDeviceIdentity().also(repository::saveMihoyoDeviceIdentity),
    )
    private val preferences = application.getSharedPreferences("ui_settings", 0)
    private val initialSchedule = AutoCheckInScheduler.currentTime(application)
    private val _state = MutableStateFlow(
        MainUiState(
            selected = preferences.getStringSet("selected_games", null)?.toSet()
                ?: GameCatalog.builtIn.filter { it.enabledByDefault }.mapTo(mutableSetOf()) { it.id },
            mihoyoLoggedIn = repository.loadMihoyo(DEFAULT_ACCOUNT) != null,
            sklandLoggedIn = repository.loadSkland(DEFAULT_ACCOUNT) != null,
            scheduleHour = initialSchedule.hour,
            scheduleMinute = initialSchedule.minute,
            autoCheckInSnapshot = autoCheckInStatusStore.load(),
        ),
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    fun toggleGame(id: String, checked: Boolean) {
        val selected = _state.value.selected.toMutableSet().apply {
            if (checked) add(id) else remove(id)
        }
        preferences.edit().putStringSet("selected_games", selected).apply()
        _state.value = _state.value.copy(selected = selected)
    }

    fun updateSchedule(hour: Int, minute: Int) {
        AutoCheckInScheduler.updateTime(getApplication(), hour, minute)
        _state.value = _state.value.copy(scheduleHour = hour, scheduleMinute = minute)
    }

    fun refreshAutoCheckInStatus() {
        _state.value = _state.value.copy(autoCheckInSnapshot = autoCheckInStatusStore.load())
    }

    fun beginMihoyoLogin() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, qrStatus = "正在创建游戏登录二维码……")
            val session = qrClient.createGameQr().getOrElse {
                _state.value = _state.value.copy(busy = false, qrStatus = "二维码创建失败：${it.message}")
                return@launch
            }
            _state.value = _state.value.copy(
                busy = false,
                qrSession = session,
                qrStatus = "推荐方式：游戏扫码登录。请使用米游社扫描并确认原神登录授权",
            )
            pollMihoyoQr(session)
        }
    }

    fun cancelMihoyoLogin() {
        _state.value = _state.value.copy(qrSession = null, qrStatus = null)
    }

    private suspend fun pollMihoyoQr(session: MihoyoQrSession) {
        repeat(90) {
            if (_state.value.qrSession?.ticket != session.ticket) return
            when (val result = qrClient.queryQr(session).getOrElse { MihoyoQrState.Failed(it.message ?: "查询失败") }) {
                MihoyoQrState.Waiting -> _state.value = _state.value.copy(qrStatus = "等待扫码……")
                MihoyoQrState.Scanned -> _state.value = _state.value.copy(qrStatus = "已扫码，请在米游社中确认游戏登录")
                is MihoyoQrState.Confirmed -> {
                    _state.value = _state.value.copy(qrStatus = "正在交换并安全保存签到凭证……")
                    val credential = qrClient.exchangeCredential(session, result).getOrElse {
                        _state.value = _state.value.copy(qrSession = null, qrStatus = "登录失败：${it.message}")
                        return
                    }
                    repository.saveMihoyo(DEFAULT_ACCOUNT, credential)
                    _state.value = _state.value.copy(
                        mihoyoLoggedIn = true,
                        qrSession = null,
                        qrStatus = "米游社游戏扫码登录成功",
                    )
                    return
                }
                is MihoyoQrState.Failed -> {
                    _state.value = _state.value.copy(qrSession = null, qrStatus = "二维码失效：${result.message}")
                    return
                }
            }
            delay(2_000)
        }
        _state.value = _state.value.copy(qrSession = null, qrStatus = "二维码已超时，请重新获取")
    }

    fun saveSklandToken(token: String) {
        if (token.isBlank()) return
        repository.saveSklandToken(DEFAULT_ACCOUNT, token)
        _state.value = _state.value.copy(sklandLoggedIn = true, output = listOf("${nowLabel()} 森空岛登录信息已加密保存"))
    }

    fun runSelectedCheckIns() {
        if (_state.value.busy) return
        viewModelScope.launch {
            val taskId = DevLogger.newTaskId()
            val lines = mutableListOf<String>()
            _state.value = _state.value.copy(busy = true, output = listOf("${nowLabel()} 开始检查已选游戏……"))
            val games = GameCatalog.builtIn.filter { it.id in _state.value.selected }
            val providers = mapOf(
                ProviderType.MIHOYO to repository.loadMihoyo(DEFAULT_ACCOUNT)?.let(::MihoyoProvider),
                ProviderType.SKLAND to repository.loadSkland(DEFAULT_ACCOUNT)?.let { credential ->
                    SklandProvider(credential, onCredentialUpdated = { repository.saveSkland(DEFAULT_ACCOUNT, it) })
                },
            )
            val requestPacer = CheckInRequestPacer()
            for ((providerType, providerGames) in games.groupBy { it.provider }) {
                val provider = providers[providerType]
                if (provider == null) {
                    lines += "${nowLabel()} ${providerName(providerType)}：尚未登录"
                    continue
                }
                val validation = provider.validateCredential()
                if (validation.isFailure) {
                    lines += "${nowLabel()} ${providerName(providerType)}：登录验证失败（${validation.exceptionOrNull()?.message}）"
                    continue
                }
                var stopProvider = false
                for (game in providerGames) {
                    if (stopProvider) break
                    val roleResult = provider.getRoles(game)
                    if (roleResult.isFailure) {
                        lines += "${nowLabel()} ${game.displayName}：读取角色失败（${roleResult.exceptionOrNull()?.message}）"
                        continue
                    }
                    val roles = roleResult.getOrThrow()
                    if (roles.isEmpty()) lines += "${nowLabel()} ${game.displayName}：没有找到绑定角色"
                    for (role in roles) {
                        val label = "${game.displayName} · ${role.nickname}"
                        requestPacer.awaitTurn()
                        lines += "${nowLabel()} $label：开始请求"
                        _state.value = _state.value.copy(output = lines.toList())
                        when (val result = provider.checkIn(game, role)) {
                            is CheckInResult.Success -> lines += "${nowLabel()} $label：${result.message}"
                            CheckInResult.AlreadyCheckedIn -> lines += "${nowLabel()} $label：今日已签到"
                            is CheckInResult.Unknown -> lines += "${nowLabel()} $label：结果未知（${result.message}）"
                            is CheckInResult.Failure -> {
                                lines += "${nowLabel()} $label：失败（${result.message}）"
                                if (result.code == "CAPTCHA_REQUIRED") {
                                    lines += "${nowLabel()} 检测到人工验证要求，已停止${providerName(providerType)}后续请求"
                                    stopProvider = true
                                    break
                                }
                            }
                        }
                        _state.value = _state.value.copy(output = lines.toList())
                    }
                }
            }
            DevLogger.info("任务", "签到测试结束，共 ${lines.size} 条结果", taskId)
            _state.value = _state.value.copy(busy = false, output = lines.ifEmpty { listOf("${nowLabel()} 没有选择游戏") })
        }
    }

    fun refreshLogs() {
        _state.value = _state.value.copy(
            output = DevLogger.snapshot().takeLast(100).map(DevLogFormatter::format),
        )
    }

    private fun nowLabel(): String = TIME_FORMATTER.format(LocalTime.now())

    private fun providerName(type: ProviderType) = if (type == ProviderType.MIHOYO) "米游社" else "森空岛"

    private companion object {
        const val DEFAULT_ACCOUNT = "default"
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
