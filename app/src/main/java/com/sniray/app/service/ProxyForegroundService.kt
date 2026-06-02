package com.sniray.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.sniray.app.rsta.LocalPortProbe
import com.sniray.app.rsta.RstaProxyServiceState
import com.sniray.app.R
import com.sniray.app.v2ray.handler.NotificationManager as VpnNotificationManager
import com.sniray.app.data.AppDatabase
import com.sniray.app.data.ProxyConfigEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class ProxyForegroundService : Service() {

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var childProcess: java.lang.Process? = null
    private var logThread: Thread? = null

    private val _runState = MutableStateFlow<ProxyRunState>(ProxyRunState.Idle)
    val runState: StateFlow<ProxyRunState> = _runState.asStateFlow()

    private fun publishRunState(state: ProxyRunState) {
        _runState.value = state
        RstaProxyServiceState.updateRunState(state)
    }

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val maxLogLines = 2000
    private val stopped = AtomicBoolean(false)

    inner class LocalBinder : Binder() {
        fun getService(): ProxyForegroundService = this@ProxyForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val configId = intent.getLongExtra(EXTRA_CONFIG_ID, -1L)
                if (configId < 0) {
                    publishRunState(ProxyRunState.Error("Missing config"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                val config = runBlocking {
                    AppDatabase.get(this@ProxyForegroundService).proxyConfigDao().getById(configId)
                }
                if (config == null) {
                    publishRunState(ProxyRunState.Error("Config not found"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProxy(config)
            }
        }
        return START_NOT_STICKY
    }

    private fun startProxy(config: ProxyConfigEntity) {
        stopProxyInternal()
        stopped.set(false)
        publishRunState(ProxyRunState.Starting)
        _logs.value = emptyList()
        RstaProxyServiceState.clearLogs()

        val notification = VpnNotificationManager.buildSharedVpnForegroundNotification(
            this,
            statusLine = getString(R.string.notification_sni_starting),
        )
        val notificationId = VpnNotificationManager.VPN_FOREGROUND_NOTIFICATION_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(notificationId, notification, fgsType)
        } else {
            @Suppress("DEPRECATION")
            startForeground(notificationId, notification)
        }

        try {
            val configFile = ProxyConfigFile.write(this, config)
            appendLog(
                "Config: ${config.listenHost}:${config.listenPort} → " +
                    "${config.connectHost}:${config.connectPort} sni=${config.fakeSni} method=${config.method}",
            )
            val command = BinaryExtractor.commandLine(
                this,
                "-config", configFile.absolutePath,
            )
            val proc = ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(filesDir)
                .apply {
                    environment().put("FORCE_COLOR", "1")
                    environment().put("TERM", "xterm-256color")
                }
                .start()

            childProcess = proc

            logThread = Thread {
                readLogs(proc)
            }.apply { name = "rstaspoof-log"; start() }

            Thread {
                val ready = waitForListenPort(config.listenPort, timeoutMs = 20_000)
                mainHandler.post {
                    if (stopped.get()) return@post
                    if (ready && childProcess != null) {
                        publishRunState(ProxyRunState.Running(config))
                    } else {
                        val msg = _logs.value.lastOrNull()
                            ?: "Port ${config.listenPort} not listening (is librstaspoof.so built?)"
                        appendLog("Error: $msg")
                        publishRunState(ProxyRunState.Error(msg))
                        stopProxyInternal()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }.apply { name = "rstaspoof-port-wait"; start() }
        } catch (e: Exception) {
            appendLog("Error: ${e.message}")
            publishRunState(ProxyRunState.Error(e.message ?: "Failed to start"))
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun readLogs(proc: java.lang.Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (stopped.get()) break
                    line?.let { appendLog(it) }
                }
            }
            val exit = proc.waitFor()
            if (!stopped.get()) {
                appendLog("Process exited with code $exit")
                mainHandler.post {
                    publishRunState(ProxyRunState.Idle)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        } catch (e: Exception) {
            if (!stopped.get()) {
                appendLog("Log reader error: ${e.message}")
            }
        }
    }

    private fun appendLog(line: String) {
        val current = _logs.value
        val next = (current + line).let { list ->
            if (list.size > maxLogLines) list.takeLast(maxLogLines) else list
        }
        _logs.value = next
        RstaProxyServiceState.appendLog(line)
    }

    private fun waitForListenPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (LocalPortProbe.isOpen(port)) return true
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return LocalPortProbe.isOpen(port)
    }

    private fun stopProxy() {
        stopProxyInternal()
        publishRunState(ProxyRunState.Idle)
        RstaProxyServiceState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProxyInternal() {
        stopped.set(true)
        logThread?.interrupt()
        logThread = null
        childProcess?.let { proc ->
            proc.destroy()
            try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                proc.destroyForcibly()
            }
        }
        childProcess = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        stopProxyInternal()
        super.onDestroy()
    }

    /**
     * Drops the proxy FGS notification once [com.sniray.app.v2ray.handler.NotificationManager]
     * shows the VPN notification (only notification the user should see).
     */
    /**
     * Leaves the shared VPN notification on screen when [VpnNotificationManager.showNotification]
     * takes over on the VPN service (API 33+). Older APIs keep the same notification slot (ID 1).
     */
    fun detachNotificationForVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    companion object {
        @Volatile
        private var instance: ProxyForegroundService? = null

        fun detachNotificationForUnifiedVpn() {
            instance?.detachNotificationForVpn()
        }

        const val ACTION_START = "com.sniray.app.START"
        const val ACTION_STOP = "com.sniray.app.STOP"
        const val EXTRA_CONFIG_ID = "config_id"

        fun startIntent(context: Context, configId: Long): Intent =
            Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG_ID, configId)
            }
    }
}
