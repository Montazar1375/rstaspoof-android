package com.sniray.app.vm

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sniray.app.data.AppDatabase
import com.sniray.app.data.ProxyConfigEntity
import com.sniray.app.data.ProxyConfigSeeder
import com.sniray.app.data.ProxyRepository
import com.sniray.app.rsta.RstaBypassConfigInjector
import com.sniray.app.rsta.RstaProxyServiceState
import com.sniray.app.rsta.UnifiedVpnOrchestrator
import com.sniray.app.service.ProxyForegroundService
import com.sniray.app.R
import com.sniray.app.service.ProxyRunState
import com.sniray.app.v2ray.core.CoreServiceManager
import com.sniray.app.v2ray.extension.toast
import com.sniray.app.v2ray.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProxyRepository(AppDatabase.get(application).proxyConfigDao())

    val configs = repository.observeConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private val _runState = MutableStateFlow<ProxyRunState>(ProxyRunState.Idle)
    val runState: StateFlow<ProxyRunState> = _runState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _bypassChainEnabled = MutableStateFlow(RstaBypassConfigInjector.isBypassEnabled())
    val bypassChainEnabled: StateFlow<Boolean> = _bypassChainEnabled.asStateFlow()

    private var service: ProxyForegroundService? = null
    private var bound = false
    private var collecting = false
    private var runningConfigId: Long? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as ProxyForegroundService.LocalBinder
            service = local.getService()
            bound = true
            collectFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            collecting = false
        }
    }

    init {
        viewModelScope.launch {
            ProxyConfigSeeder.seedIfEmpty(getApplication())
            val activeId = RstaBypassConfigInjector.getActiveBypassConfigId()
            if (activeId >= 0L) {
                _selectedId.value = activeId
            }
        }
        viewModelScope.launch {
            RstaProxyServiceState.logs.collect { lines ->
                _logs.value = lines
            }
        }
    }

    /** Persists chain toggle and updates UI state (restart is handled by [MainActivity.onBypassChainSwitchChanged]). */
    fun updateBypassChainEnabled(enabled: Boolean) {
        RstaBypassConfigInjector.setBypassEnabled(enabled)
        _bypassChainEnabled.value = enabled
    }

    fun onVpnRestartStarted() {
        _runState.value = ProxyRunState.Starting
    }

    fun selectConfig(id: Long) {
        val state = _runState.value
        if (state is ProxyRunState.Running || state is ProxyRunState.Starting) {
            return
        }
        _selectedId.value = id
        RstaBypassConfigInjector.setActiveBypassConfigId(id)
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Returns a VpnService. Prepare intent if user consent is required, or null if ready to connect.
     */
    fun prepareVpnIntent(): Intent? {
        return VpnService.prepare(getApplication())
    }

    fun startUnifiedVpn() {
        val bypassId = _selectedId.value
        if (bypassId == null) {
            _statusMessage.value = "Select an SNI bypass profile first"
            return
        }
        val v2rayGuid = MmkvManager.getSelectServer()
        if (v2rayGuid.isNullOrEmpty()) {
            _statusMessage.value = "Add and select a v2ray server in the Servers tab"
            return
        }
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _runState.value = ProxyRunState.Starting
            repository.touch(bypassId)
            runningConfigId = bypassId
            val bypass = repository.getById(bypassId)
            if (bypass == null) {
                _runState.value = ProxyRunState.Error("Bypass config not found")
                return@launch
            }
            appendLogLine("[connect] Starting SNI bypass on 127.0.0.1:${bypass.listenPort}…")
            appendLogLine("[connect] Starting VPN (server → loopback)…")
            val result = UnifiedVpnOrchestrator.start(ctx, bypass, v2rayGuid)
            result.fold(
                onSuccess = {
                    bindService()
                    _runState.value = ProxyRunState.Running(bypass)
                    appendLogLine("[connect] VPN running")
                },
                onFailure = { e ->
                    _runState.value = ProxyRunState.Error(e.message ?: "Connect failed")
                    appendLogLine("[connect] Error: ${e.message}")
                },
            )
        }
    }

    fun stopProxy() {
        val ctx = getApplication<Application>()
        runningConfigId = null
        UnifiedVpnOrchestrator.stop(ctx)
        unbindService()
        _runState.value = ProxyRunState.Idle
        appendLogLine("[connect] Stopped")
    }

    fun toggleProxy() {
        val state = _runState.value
        if (state is ProxyRunState.Running || state is ProxyRunState.Starting) {
            stopProxy()
        } else {
            startUnifiedVpn()
        }
    }

    fun isVpnCoreRunning(): Boolean = CoreServiceManager.isRunning()

    private fun appendLogLine(line: String) {
        RstaProxyServiceState.appendLog(line)
    }

    fun bindIfRunning() {
        syncFromVpnCoreState()
    }

    /** Refresh log UI from global proxy state (works without service bind). */
    fun refreshLogsFromServiceState() {
        val global = RstaProxyServiceState.logs.value
        if (global.isNotEmpty()) {
            _logs.value = global
        }
        syncFromVpnCoreState()
        service?.logs?.value?.let { svcLogs ->
            if (svcLogs.isNotEmpty()) _logs.value = svcLogs
        }
    }

    /** Keep bypass/logs UI in sync when connect/stop happens from the V2ray FAB. */
    fun syncFromVpnCoreState() {
        _bypassChainEnabled.value = RstaBypassConfigInjector.isBypassEnabled()
        if (!CoreServiceManager.isRunning()) {
            if (_runState.value is ProxyRunState.Running || _runState.value is ProxyRunState.Starting) {
                _runState.value = ProxyRunState.Idle
            }
            return
        }
        viewModelScope.launch {
            val configId = RstaBypassConfigInjector.getActiveBypassConfigId()
            val bypass = if (configId >= 0) repository.getById(configId) else null
            if (bypass != null) {
                runningConfigId = bypass.id
                _runState.value = ProxyRunState.Running(bypass)
                bindService()
            }
        }
    }

    private fun bindService() {
        val ctx = getApplication<Application>()
        if (!bound) {
            ctx.bindService(
                Intent(ctx, ProxyForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } else {
            collectFromService()
        }
    }

    private fun collectFromService() {
        val svc = service ?: return
        svc.logs.value.let { snapshot ->
            if (snapshot.isNotEmpty()) _logs.value = snapshot
        }
        if (collecting) return
        collecting = true
        viewModelScope.launch {
            svc.runState.collect { state ->
                if (state is ProxyRunState.Running) {
                    _runState.value = state
                }
            }
        }
        viewModelScope.launch {
            svc.logs.collect { lines ->
                if (lines.isNotEmpty()) _logs.value = lines
            }
        }
    }

    fun unbindService() {
        if (bound) {
            try {
                getApplication<Application>().unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
            bound = false
            service = null
            collecting = false
        }
    }

    suspend fun getConfig(id: Long): ProxyConfigEntity? = repository.getById(id)

    suspend fun saveConfig(config: ProxyConfigEntity): Long = repository.save(config)

    suspend fun deleteConfig(config: ProxyConfigEntity) {
        val state = _runState.value
        if (state is ProxyRunState.Running || state is ProxyRunState.Starting) return
        if (_selectedId.value == config.id) _selectedId.value = null
        repository.delete(config)
    }

    override fun onCleared() {
        unbindService()
        super.onCleared()
    }
}
