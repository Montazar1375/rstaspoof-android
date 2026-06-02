package com.rstaspoof.app.vm

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rstaspoof.app.data.AppDatabase
import com.rstaspoof.app.data.ProxyConfigEntity
import com.rstaspoof.app.data.ProxyRepository
import com.rstaspoof.app.service.NetworkChangeMonitor
import com.rstaspoof.app.service.ProxyForegroundService
import com.rstaspoof.app.service.ProxyRunState
import kotlinx.coroutines.delay
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

    private var service: ProxyForegroundService? = null
    private var bound = false
    private var collecting = false
    private var runningConfigId: Long? = null

    private val networkMonitor = NetworkChangeMonitor(application) {
        restartProxyAfterNetworkChange()
    }

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
        }
    }

    init {
        networkMonitor.start()
        viewModelScope.launch {
            seedDefaultIfEmpty()
        }
    }

    private suspend fun seedDefaultIfEmpty() {
        val dao = AppDatabase.get(getApplication()).proxyConfigDao()
        if (dao.count() == 0) {
            val id = repository.save(
                ProxyConfigEntity(
                    name = "Default",
                    listenHost = "0.0.0.0",
                    listenPort = 40443,
                    connectHost = "104.19.229.21",
                    connectPort = 443,
                    fakeSni = "www.hcaptcha.com",
                    method = "combined",
                ),
            )
            _selectedId.value = id
        }
    }

    fun selectConfig(id: Long) {
        val state = _runState.value
        if (state is ProxyRunState.Running || state is ProxyRunState.Starting) {
            return
        }
        _selectedId.value = id
    }

    fun startProxy(configId: Long) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            repository.touch(configId)
            _selectedId.value = configId
            runningConfigId = configId
            val intent = ProxyForegroundService.startIntent(ctx, configId)
            ContextCompat.startForegroundService(ctx, intent)
            bindService()
        }
    }

    fun stopProxy() {
        val ctx = getApplication<Application>()
        runningConfigId = null
        ctx.startService(
            Intent(ctx, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_STOP
            },
        )
        unbindService()
        _runState.value = ProxyRunState.Idle
    }

    fun toggleProxy() {
        val state = _runState.value
        if (state is ProxyRunState.Running || state is ProxyRunState.Starting) {
            stopProxy()
        } else {
            val id = _selectedId.value ?: return
            startProxy(id)
        }
    }

    private fun restartProxyAfterNetworkChange() {
        val configId = runningConfigId ?: return
        val state = _runState.value
        if (state !is ProxyRunState.Running && state !is ProxyRunState.Starting) return
        viewModelScope.launch {
            appendLogLine("[network] connectivity changed — restarting proxy…")
            stopProxy()
            runningConfigId = configId
            delay(300)
            startProxy(configId)
        }
    }

    private fun appendLogLine(line: String) {
        val current = _logs.value
        _logs.value = (current + line).takeLast(2000)
    }

    fun bindIfRunning() {
        bindService()
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
        if (collecting) return
        collecting = true
        viewModelScope.launch {
            svc.runState.collect { _runState.value = it }
        }
        viewModelScope.launch {
            svc.logs.collect { _logs.value = it }
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
        networkMonitor.stop()
        unbindService()
        super.onCleared()
    }
}
