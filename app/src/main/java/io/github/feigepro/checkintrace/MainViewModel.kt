package io.github.feigepro.checkintrace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.feigepro.checkintrace.data.GameCatalog
import io.github.feigepro.checkintrace.data.ProviderType
import io.github.feigepro.checkintrace.logging.DevLogFormatter
import io.github.feigepro.checkintrace.logging.DevLogger
import io.github.feigepro.checkintrace.logging.LogRedactor
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoDeviceIdentityStore
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoProvider
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoQrLoginClient
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoQrSession
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoQrState
import io.github.feigepro.checkintrace.provider.skland.SklandProvider
import io.github.feigepro.checkintrace.security.CredentialRepository
import io.github.feigepro.checkintrace.security.EncryptedCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

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
        deviceIdentity = MihoyoDeviceIdentityStore(application).getOrCreate(),
    )
    private var mihoyoLoginJob: kotlinx.coroutines.Job? = null
    private var mihoyoLoginGeneration = 0L
    private val preferences = application.getSharedPreferences("ui_settings", 0)
    private val initialSchedule = AutoCheckInScheduler.currentTime(application)
    private val _state = MutableStateFlow(
        MainUiState(
            selected = preferences.getStringSet("selected_games", null)?.toSet()
                ?: GameCatalog.builtIn.filter { it.enabledByDefault }.mapTo(mutableSetOf()) { it.id },
            mihoyoLoggedIn = repository.loadMihoyo(DEFAULT_ACCOUNT) != null,
            sklandLoggedIn = repository.loadSklandToken(DEFAULT_ACCOUNT) != null,
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
        if (!preferences.edit().putStringSet("selected_games", selected).commit()) {
            DevLogger.error("设置", "保存游戏选择失败")
            return
        }
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
        mihoyoLoginJob?.cancel()
        val generation = ++mihoyoLoginGeneration
        mihoyoLoginJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, qrStatus = "正在创建二维码……")
            try {
                val session = qrClient.createQr().getOrElse {
                    _state.value = _state.value.copy(qrStatus = "二维码创建失败：${it.message}")
                    return@launch
                }
                if (generation != mihoyoLoginGeneration) return@launch
                _state.value = _state.value.copy(qrSession = session, qrStatus = "请使用米游社扫描并确认")
                pollMihoyoQr(session, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = LogRedactor.redact(error.message ?: error.javaClass.simpleName)
                DevLogger.error("米游社/登录", message)
                _state.value = _state.value.copy(qrSession = null, qrStatus = "登录流程异常：$message")
            } finally {
                if (generation == mihoyoLoginGeneration) {
                    _state.value = _state.value.copy(busy = false)
                    mihoyoLoginJob = null
                }
            }
        }
    }

    fun cancelMihoyoLogin() {
        mihoyoLoginGeneration++
        mihoyoLoginJob?.cancel()
        mihoyoLoginJob = null
        _state.value = _state.value.copy(busy = false, qrSession = null, qrStatus = null)
    }

    private suspend fun pollMihoyoQr(session: MihoyoQrSession, generation: Long) {
        var transientFailures = 0
        repeat(90) {
            if (generation != mihoyoLoginGeneration || _state.value.qrSession?.ticket != session.ticket) return
            val query = qrClient.queryQr(session)
            if (generation != mihoyoLoginGeneration || _state.value.qrSession?.ticket != session.ticket) return
            if (query.isFailure) {
                transientFailures += 1
                if (transientFailures >= QR_TRANSIENT_FAILURE_LIMIT) {
                    _state.value = _state.value.copy(qrSession = null, qrStatus = "二维码查询失败：${query.exceptionOrNull()?.message}")
                    return
                }
                _state.value = _state.value.copy(qrStatus = "网络波动，继续查询（$transientFailures/$QR_TRANSIENT_FAILURE_LIMIT）")
                delay(QR_POLL_INTERVAL_MILLIS)
                return@repeat
            }
            transientFailures = 0
            when (val result = query.getOrThrow()) {
                MihoyoQrState.Waiting -> _state.value = _state.value.copy(qrStatus = "等待扫码……")
                MihoyoQrState.Scanned -> _state.value = _state.value.copy(qrStatus = "已扫码，请在米游社中确认")
                is MihoyoQrState.Confirmed -> {
                    _state.value = _state.value.copy(qrStatus = "正在安全保存登录信息……")
                    val credential = qrClient.exchangeCredential(session, result).getOrElse {
                        _state.value = _state.value.copy(qrSession = null, qrStatus = "登录失败：${it.message}")
                        return
                    }
                    if (generation != mihoyoLoginGeneration || _state.value.qrSession?.ticket != session.ticket) return
                    runCatching { repository.saveMihoyo(DEFAULT_ACCOUNT, credential) }.getOrElse {
                        throw IllegalStateException("登录凭证保存失败", it)
                    }
                    _state.value = _state.value.copy(mihoyoLoggedIn = true, qrSession = null, qrStatus = "米游社登录成功")
                    return
                }
                is MihoyoQrState.Failed -> {
                    _state.value = _state.value.copy(qrSession = null, qrStatus = "二维码失效：${result.message}")
                    return
                }
            }
            delay(QR_POLL_INTERVAL_MILLIS)
        }
        _state.value = _state.value.copy(qrSession = null, qrStatus = "二维码已超时，请重新获取")
    }

    fun saveSklandToken(token: String) {
        if (token.isBlank()) return
        repository.saveSklandToken(DEFAULT_ACCOUNT, token)
        _state.value = _state.value.copy(sklandLoggedIn = true, output = listOf("森空岛登录信息已加密保存"))
    }

    fun runSelectedCheckIns() {
        if (_state.value.busy) return
        viewModelScope.launch {
            if (!acquireCheckInExecutionLock(MANUAL_LOCK_WAIT_MILLIS)) {
                _state.value = _state.value.copy(output = listOf("已有签到任务正在执行，请稍后再试"))
                return@launch
            }
            val taskId = DevLogger.newTaskId()
            val lines = mutableListOf<String>()
            _state.value = _state.value.copy(busy = true, output = listOf("开始检查已选游戏……"))
            try {
                val games = GameCatalog.builtIn.filter { it.id in _state.value.selected }
                val providers = mapOf(
                    ProviderType.MIHOYO to repository.loadMihoyo(DEFAULT_ACCOUNT)?.let(::MihoyoProvider),
                    ProviderType.SKLAND to repository.loadSklandToken(DEFAULT_ACCOUNT)?.let(::SklandProvider),
                )
                if (games.isEmpty()) lines += "没有选择游戏"
                val outcomes = supervisorScope {
                    games.groupBy { it.provider }.map { (type, providerGames) ->
                        async {
                            executeProviderCheckInsSafely(
                                type = type,
                                providerGames = providerGames,
                                provider = providers[type],
                                previouslyCompletedRoleKeys = emptySet(),
                                skipPreviouslyCompleted = false,
                                taskId = taskId,
                            )
                        }
                    }.awaitAll()
                }
                outcomes.forEach { lines += it.lines }
                DevLogger.info("任务", "签到测试结束，共 ${lines.size} 条结果", taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = LogRedactor.redact(error.message ?: error.javaClass.simpleName)
                lines += "任务异常（$message）"
                DevLogger.error("任务", message, taskId)
            } finally {
                DevLogger.flush()
                _state.value = _state.value.copy(
                    busy = false,
                    output = lines.ifEmpty { listOf("没有选择游戏") },
                )
                CheckInExecutionLock.mutex.unlock()
            }
        }
    }

    fun refreshLogs() {
        _state.value = _state.value.copy(
            output = DevLogger.snapshot().takeLast(100).map(DevLogFormatter::format),
        )
    }

    override fun onCleared() {
        mihoyoLoginGeneration++
        mihoyoLoginJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_ACCOUNT = "default"
        const val QR_POLL_INTERVAL_MILLIS = 2_000L
        const val QR_TRANSIENT_FAILURE_LIMIT = 3
        const val MANUAL_LOCK_WAIT_MILLIS = 5_000L
    }
}
