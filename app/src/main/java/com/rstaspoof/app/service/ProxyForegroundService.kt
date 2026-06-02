package com.rstaspoof.app.service

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
import com.rstaspoof.app.MainActivity
import com.rstaspoof.app.R
import com.rstaspoof.app.data.AppDatabase
import com.rstaspoof.app.data.ProxyConfigEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class ProxyForegroundService : Service() {

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var childProcess: java.lang.Process? = null
    private var logThread: Thread? = null
    private var trafficRunnable: Runnable? = null

    private var txBaseline = 0L
    private var rxBaseline = 0L
    private val uid = android.os.Process.myUid()

    private val _runState = MutableStateFlow<ProxyRunState>(ProxyRunState.Idle)
    val runState: StateFlow<ProxyRunState> = _runState.asStateFlow()

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
                    _runState.value = ProxyRunState.Error("Missing config")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val config = runBlocking {
                    AppDatabase.get(this@ProxyForegroundService).proxyConfigDao().getById(configId)
                }
                if (config == null) {
                    _runState.value = ProxyRunState.Error("Config not found")
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
        _runState.value = ProxyRunState.Starting
        _logs.value = emptyList()

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
            _runState.value = ProxyRunState.Running(config)

            logThread = Thread {
                readLogs(proc)
            }.apply { name = "rstaspoof-log"; start() }

            startTrafficUpdates(config)
        } catch (e: Exception) {
            appendLog("Error: ${e.message}")
            _runState.value = ProxyRunState.Error(e.message ?: "Failed to start")
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
                    _runState.value = ProxyRunState.Idle
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
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(config, tx, rx, lastLog))
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
    }

    private fun stopProxy() {
        stopProxyInternal()
        _runState.value = ProxyRunState.Idle
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
        stopProxyInternal()
        super.onDestroy()
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
        const val CHANNEL_ID = "proxy_running"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rstaspoof.app.START"
        const val ACTION_STOP = "com.rstaspoof.app.STOP"
        const val EXTRA_CONFIG_ID = "config_id"

        fun startIntent(context: Context, configId: Long): Intent =
            Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG_ID, configId)
            }
    }
}
