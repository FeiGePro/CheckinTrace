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
import io.github.feigepro.checkintrace.provider.skland.SklandApi
import io.github.feigepro.checkintrace.provider.skland.SklandProvider
import io.github.feigepro.checkintrace.provider.skland.SklandQrLoginClient
import io.github.feigepro.checkintrace.provider.skland.SklandQrPollResult
import io.github.feigepro.checkintrace.provider.skland.SklandQrSession
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
    val sklandQrSession: SklandQrSession? = null,
    val sklandQrStatus: String? = null,
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
    private val sklandQrClient = SklandQrLoginClient()
    private val sklandApi = SklandApi()
    private val preferences = application.getSharedPreferences("ui_settings", 0)
    private val pendingLoginStore = PendingLoginSessionStore(application)
    private val restoredSklandQr = pendingLoginStore.loadSkland()
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
            sklandQrSession = restoredSklandQr?.session,
            sklandQrStatus = restoredSklandQr?.let { "已恢复原森空岛二维码，正在继续等待扫码确认……" },
        ),
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        restoredSklandQr?.let { pending ->
            viewModelScope.launch {
                pollSklandQr(
                    session = pending.session,
                    taskId = DevLogger.newTaskId(),
                    createdAtEpochMillis = pending.createdAtEpochMillis,
                )
            }
        }
    }

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

    fun refreshLoginStates() {
        _state.value = _state.value.copy(
            mihoyoLoggedIn = repository.loadMihoyo(DEFAULT_ACCOUNT) != null,
            sklandLoggedIn = repository.loadSkland(DEFAULT_ACCOUNT) != null,
        )
    }

    fun beginMihoyoLogin() {
        if (_state.value.busy || _state.value.qrSession != null || _state.value.sklandQrSession != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                qrStatus = "正在创建 GameToken 登录二维码……",
            )
            val session = qrClient.createQr().getOrElse {
                _state.value = _state.value.copy(busy = false, qrStatus = "二维码创建失败：${it.message}")
                return@launch
            }
            _state.value = _state.value.copy(
                busy = false,
                qrSession = session,
                qrStatus = "请使用米游社 App 扫码。若确认时出现 decode err / unexpected end of JSON input，这是当前米游社 GameToken 确认接口的上游兼容故障，请改用短信验证码备用登录。",
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
                MihoyoQrState.Scanned -> _state.value = _state.value.copy(
                    qrStatus = "已扫码，请在米游社中确认。若米游社提示 JSON 截断，这是平台当前 GameToken 确认链路故障，签迹无法在二维码生成端修复。",
                )
                is MihoyoQrState.Confirmed -> {
                    _state.value = _state.value.copy(qrStatus = "正在交换凭证并注册 Android 设备……")
                    val credential = qrClient.exchangeCredential(session, result).getOrElse {
                        _state.value = _state.value.copy(qrSession = null, qrStatus = "登录失败：${it.message}")
                        return
                    }
                    repository.saveMihoyo(DEFAULT_ACCOUNT, credential)
                    _state.value = _state.value.copy(
                        mihoyoLoggedIn = true,
                        qrSession = null,
                        qrStatus = "米游社二维码登录成功，Android 设备身份已保存",
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
        _state.value = _state.value.copy(
            qrSession = null,
            qrStatus = "二维码未能完成确认。当前 GameToken 二维码在米游社确认端存在已知 JSON 截断问题，请使用短信验证码备用登录。",
        )
    }

    fun beginSklandLogin() {
        if (_state.value.busy || _state.value.qrSession != null || _state.value.sklandQrSession != null) return
        viewModelScope.launch {
            val taskId = DevLogger.newTaskId()
            _state.value = _state.value.copy(
                busy = true,
                sklandQrStatus = "正在创建森空岛官方登录二维码……",
            )
            val session = sklandQrClient.create(taskId).getOrElse {
                _state.value = _state.value.copy(
                    busy = false,
                    sklandQrStatus = "二维码创建失败：${it.message}",
                )
                return@launch
            }
            val createdAt = System.currentTimeMillis()
            pendingLoginStore.saveSkland(session, createdAt)
            _state.value = _state.value.copy(
                busy = false,
                sklandQrSession = session,
                sklandQrStatus = "请截图后使用森空岛 App 扫码；返回签迹时会恢复同一张二维码并继续轮询",
            )
            pollSklandQr(session, taskId, createdAt)
        }
    }

    fun cancelSklandLogin() {
        pendingLoginStore.clearSkland()
        _state.value = _state.value.copy(sklandQrSession = null, sklandQrStatus = null)
    }

    private suspend fun pollSklandQr(
        session: SklandQrSession,
        taskId: String,
        createdAtEpochMillis: Long,
    ) {
        while (System.currentTimeMillis() - createdAtEpochMillis < PendingLoginSessionStore.SKLAND_QR_TTL_MILLIS) {
            if (_state.value.sklandQrSession?.scanId != session.scanId) return
            delay(2_000)
            val pollResult = sklandQrClient.poll(session, taskId)
            if (pollResult.isFailure) {
                val error = pollResult.exceptionOrNull()
                _state.value = _state.value.copy(
                    sklandQrStatus = "二维码仍已保留；轮询暂时失败，正在自动继续（${error?.message ?: "网络异常"}）",
                )
                continue
            }
            when (val result = pollResult.getOrThrow()) {
                SklandQrPollResult.Waiting -> _state.value = _state.value.copy(
                    sklandQrStatus = "等待扫码并确认……截图、切换应用或页面重建都不会更换二维码",
                )
                is SklandQrPollResult.Confirmed -> {
                    pendingLoginStore.clearSkland()
                    _state.value = _state.value.copy(
                        sklandQrStatus = "已确认，正在验证并安全保存森空岛凭证……",
                    )
                    val credential = sklandApi.exchangeToken(result.token, taskId).getOrElse {
                        _state.value = _state.value.copy(
                            sklandQrSession = null,
                            sklandQrStatus = "登录凭证验证失败：${it.message}",
                        )
                        return
                    }
                    repository.saveSkland(DEFAULT_ACCOUNT, credential)
                    DevLogger.info("森空岛/登录", "登录成功，会话凭证已加密保存", taskId)
                    _state.value = _state.value.copy(
                        sklandLoggedIn = true,
                        sklandQrSession = null,
                        sklandQrStatus = "森空岛登录成功",
                    )
                    return
                }
            }
        }
        pendingLoginStore.clearSkland()
        _state.value = _state.value.copy(
            sklandQrSession = null,
            sklandQrStatus = "二维码已过期，请重新获取",
        )
    }

    fun runSelectedCheckIns() {
        if (_state.value.busy || _state.value.qrSession != null || _state.value.sklandQrSession != null) return
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
            _state.value = _state.value.copy(
                busy = false,
                output = lines.ifEmpty { listOf("${nowLabel()} 没有选择游戏") },
            )
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
