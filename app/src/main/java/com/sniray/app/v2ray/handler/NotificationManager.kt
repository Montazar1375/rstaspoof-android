package com.sniray.app.v2ray.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.sniray.app.v2ray.AppConfig
import com.sniray.app.R
import com.sniray.app.v2ray.core.CoreServiceManager
import com.sniray.app.v2ray.dto.entities.ProfileItem
import com.sniray.app.v2ray.extension.toSpeedString
import com.sniray.app.rsta.RstaBypassConfigInjector
import com.sniray.app.rsta.RstaBypassHolder
import com.sniray.app.service.ProxyForegroundService
import com.sniray.app.v2ray.ui.MainActivity
import kotlinx.coroutines.runBlocking
import com.sniray.app.v2ray.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

object NotificationManager {
    /** Shared by VPN core and SNI bypass foreground services (single shade entry). */
    const val VPN_FOREGROUND_NOTIFICATION_ID = 1

    private const val NOTIFICATION_ID = VPN_FOREGROUND_NOTIFICATION_ID
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_ICON_THRESHOLD = 3000
    private const val QUERY_INTERVAL_MS = 3000L

    private var lastQueryTime = 0L
    private var mBuilder: NotificationCompat.Builder? = null
    private var speedNotificationJob: Job? = null
    private var mNotificationManager: NotificationManager? = null

    /**
     * Starts the speed notification.
     * @param currentConfig The current profile configuration.
     */
    fun startSpeedNotification() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) return
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        var lastZeroSpeed = false

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                lastZeroSpeed = updateSpeedNotificationOnce(lastZeroSpeed)
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    /**
     * Builds the shared VPN / SNI foreground notification (same ID for both services).
     *
     * @param statusLine When set, shown as the title (e.g. while rstaspoof is starting).
     */
    fun buildSharedVpnForegroundNotification(
        context: Context,
        currentConfig: ProfileItem? = null,
        statusLine: String? = null,
    ): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(context, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_PENDING_INTENT_CONTENT,
            startMainIntent,
            flags,
        )

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            `package` = AppConfig.ANG_PACKAGE
            putExtra("key", AppConfig.MSG_STATE_STOP)
        }
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_PENDING_INTENT_STOP_V2RAY,
            stopV2RayIntent,
            flags,
        )

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            `package` = AppConfig.ANG_PACKAGE
            putExtra("key", AppConfig.MSG_STATE_RESTART)
        }
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_PENDING_INTENT_RESTART_V2RAY,
            restartV2RayIntent,
            flags,
        )

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureNotificationChannel(context)
        } else {
            ""
        }

        var title = statusLine ?: currentConfig?.remarks.orEmpty()
        var subtitle: String? = null
        if (statusLine == null && RstaBypassConfigInjector.isBypassEnabled()) {
            val bypass = runBlocking {
                RstaBypassHolder.loadBypassConfig(context)
            }
            if (bypass != null) {
                subtitle = context.getString(
                    R.string.rsta_bypass_route_hint,
                    bypass.listenPort,
                    bypass.connectHost,
                    bypass.connectPort,
                )
                if (title.isNotEmpty()) {
                    title = "$title · ${bypass.name}"
                } else {
                    title = bypass.name
                }
            }
        }
        if (title.isEmpty()) {
            title = context.getString(R.string.app_name)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setSubText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_delete_24dp,
                context.getString(R.string.notification_action_stop_v2ray),
                stopV2RayPendingIntent,
            )
            .addAction(
                R.drawable.ic_delete_24dp,
                context.getString(R.string.title_service_restart),
                restartV2RayPendingIntent,
            )

        mBuilder = builder
        return builder.build()
    }

    /**
     * Shows the notification on the VPN service.
     * @param currentConfig The current profile configuration.
     */
    fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return
        lastQueryTime = System.currentTimeMillis()
        val notification = buildSharedVpnForegroundNotification(service, currentConfig)
        service.startForeground(NOTIFICATION_ID, notification)
        ProxyForegroundService.detachNotificationForUnifiedVpn()
    }

    /**
     * Cancels the notification.
     */
    fun cancelNotification() {
        val service = getService() ?: return
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

        mBuilder = null
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        mNotificationManager = null
    }

    /**
     * Stops the speed notification.
     */
    fun stopSpeedNotification() {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            updateNotification("", 0, 0)
        }
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureNotificationChannel(context: Context): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = AppConfig.RAY_NG_CHANNEL_NAME
        val sysNm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (sysNm.getNotificationChannel(channelId) != null) {
            return channelId
        }
        val chan = NotificationChannel(
            channelId,
            channelName,
            android.app.NotificationManager.IMPORTANCE_HIGH,
        )
        chan.lightColor = Color.DKGRAY
        chan.importance = android.app.NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        sysNm.createNotificationChannel(chan)
        return channelId
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val service = getService() ?: return AppConfig.RAY_NG_CHANNEL_ID
        return ensureNotificationChannel(service)
    }

    /**
     * Updates the notification with the given content text and traffic data.
     * @param contentText The content text.
     * @param proxyTraffic The proxy traffic.
     * @param directTraffic The direct traffic.
     */
    private fun updateNotification(contentText: String?, proxyTraffic: Long, directTraffic: Long) {
        if (mBuilder != null) {
            if (proxyTraffic < NOTIFICATION_ICON_THRESHOLD && directTraffic < NOTIFICATION_ICON_THRESHOLD) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_name)
            } else if (proxyTraffic > directTraffic) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_proxy)
            } else {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_direct)
            }
            mBuilder?.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            mBuilder?.setContentText(contentText)
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    /**
     * Appends the speed string to the given text.
     * @param text The text to append to.
     * @param name The name of the tag.
     * @param up The uplink speed.
     * @param down The downlink speed.
     */
    private fun appendSpeedString(text: StringBuilder, name: String?, up: Double, down: Double) {
        var n = name ?: "no tag"
        n = n.take(min(n.length, 6))
        text.append(n)
        for (i in n.length..6 step 2) {
            text.append("\t")
        }
        text.append("•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n")
    }

    /**
     * Updates the speed notification once.
     * Queries traffic stats, separates proxy and direct, and updates the notification.
     * @param lastZeroSpeed The previous zero speed state.
     * @return The current zero speed state.
     */
    private fun updateSpeedNotificationOnce(lastZeroSpeed: Boolean): Boolean {
        val queryTime = System.currentTimeMillis()
        val sinceLastQueryIn = (queryTime - lastQueryTime)

        // If the query interval is too short, skip this round to avoid excessive CPU usage
        if (sinceLastQueryIn < QUERY_INTERVAL_MS) {
            LogUtil.w(AppConfig.TAG, "Query interval too short: ${sinceLastQueryIn}ms, skipping")
            lastQueryTime = queryTime
            return lastZeroSpeed
        }
        val sinceLastQueryInSeconds = sinceLastQueryIn / 1000.0

        var proxyUplink = 0L
        var proxyDownlink = 0L
        var directUplink = 0L
        var directDownlink = 0L

        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
            when {
                stat.tag == AppConfig.TAG_DIRECT -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> directUplink += stat.value
                        AppConfig.DOWNLINK -> directDownlink += stat.value
                    }
                }

                stat.tag.startsWith(AppConfig.TAG_PROXY) -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> proxyUplink += stat.value
                        AppConfig.DOWNLINK -> proxyDownlink += stat.value
                    }
                }
            }
        }

        val proxyTotal = proxyUplink + proxyDownlink
        val directTotal = directUplink + directDownlink
        val zeroSpeed = proxyTotal + directTotal == 0L
        if (!zeroSpeed || !lastZeroSpeed) {
            val text = StringBuilder()
            appendSpeedString(
                text, AppConfig.TAG_PROXY,
                proxyUplink / sinceLastQueryInSeconds,
                proxyDownlink / sinceLastQueryInSeconds
            )

            appendSpeedString(
                text, AppConfig.TAG_DIRECT,
                directUplink / sinceLastQueryInSeconds,
                directDownlink / sinceLastQueryInSeconds
            )
            updateNotification(text.toString(), proxyTotal, directTotal)
        }
        lastQueryTime = queryTime
        return zeroSpeed
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }
}