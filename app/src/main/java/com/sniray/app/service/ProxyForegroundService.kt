package com.sniray.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.sniray.app.rsta.LocalPortProbe
import com.sniray.app.rsta.RstaProxyServiceState
import com.sniray.app.v2ray.ui.MainActivity
import com.sniray.app.R
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
    private var trafficRunnable: Runnable? = null

    private var txBaseline = 0L
    private var rxBaseline = 0L
    private val uid = android.os.Process.myUid()

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
    private var notificationDetached = false

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

        notificationDetached = false
        createNotificationChannel()
        val notification = buildNotification(config, 0, 0, "Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
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
            txBaseline = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0
            rxBaseline = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0

            logThread = Thread {
                readLogs(proc)
            }.apply { name = "rstaspoof-log"; start() }

            Thread {
                val ready = waitForListenPort(config.listenPort, timeoutMs = 20_000)
                mainHandler.post {
                    if (stopped.get()) return@post
                    if (ready && childProcess != null) {
                        publishRunState(ProxyRunState.Running(config))
                        startTrafficUpdates(config)
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

    private fun startTrafficUpdates(config: ProxyConfigEntity) {
        trafficRunnable = object : Runnable {
            override fun run() {
                if (stopped.get() || _runState.value !is ProxyRunState.Running) return
                val tx = (TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0) - txBaseline
                val rx = (TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0) - rxBaseline
                val lastLog = _logs.value.lastOrNull()?.take(80) ?: ""
                if (!notificationDetached) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification(config, tx, rx, lastLog))
                }
                mainHandler.postDelayed(this, 2000)
            }
        }
        mainHandler.post(trafficRunnable!!)
    }

    private fun stopTrafficUpdates() {
        trafficRunnable?.let { mainHandler.removeCallbacks(it) }
        trafficRunnable = null
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
        stopTrafficUpdates()
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

    /** Hide the separate proxy notification when the VPN foreground notification is shown. */
    fun detachNotificationForVpn() {
        notificationDetached = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private fun buildNotification(
        config: ProxyConfigEntity,
        tx: Long,
        rx: Long,
        subtitle: String,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${config.name} — Running")
            .setContentText("↑ ${formatBytes(tx)}  ↓ ${formatBytes(rx)}")
            .setSubText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun formatBytes(n: Long): String = when {
        n < 1024 -> "${n}B"
        n < 1024 * 1024 -> "${n / 1024}KB"
        else -> String.format("%.1fMB", n / (1024.0 * 1024.0))
    }

    companion object {
        @Volatile
        private var instance: ProxyForegroundService? = null

        fun detachNotificationForUnifiedVpn() {
            instance?.detachNotificationForVpn()
        }

        const val CHANNEL_ID = "proxy_running"
        const val NOTIFICATION_ID = 1001
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
