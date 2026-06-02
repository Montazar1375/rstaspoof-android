package com.sniray.app.rsta

import com.sniray.app.service.ProxyRunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Global rstaspoof foreground service state for VPN bootstrap and UI sync. */
object RstaProxyServiceState {
    private const val MAX_LOG_LINES = 2000

    private val _runState = MutableStateFlow<ProxyRunState>(ProxyRunState.Idle)
    val runState: StateFlow<ProxyRunState> = _runState.asStateFlow()

    private val _lastLogLine = MutableStateFlow<String?>(null)
    val lastLogLine: StateFlow<String?> = _lastLogLine.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun updateRunState(state: ProxyRunState) {
        _runState.value = state
    }

    fun updateLastLog(line: String?) {
        _lastLogLine.value = line
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _lastLogLine.value = null
    }

    fun appendLog(line: String) {
        val next = (_logs.value + line).let { list ->
            if (list.size > MAX_LOG_LINES) list.takeLast(MAX_LOG_LINES) else list
        }
        _logs.value = next
        _lastLogLine.value = line
    }

    fun reset() {
        _runState.value = ProxyRunState.Idle
        clearLogs()
    }
}
