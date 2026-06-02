package com.sniray.app.rsta

import android.content.Context
import com.sniray.app.service.NetworkChangeMonitor
import com.sniray.app.service.ProxyForegroundService
import com.sniray.app.v2ray.AngApplication
import com.sniray.app.v2ray.core.CoreServiceManager
import com.sniray.app.v2ray.handler.SettingsManager
import com.sniray.app.v2ray.util.LogUtil
import com.sniray.app.v2ray.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restarts rstaspoof (and refreshes the VPN tunnel / Xray loop) when the underlying
 * network changes (e.g. Wi‑Fi off → mobile data) while SNI bypass + VPN are active.
 */
object UnifiedVpnNetworkRecovery {

    @Volatile
    private var monitor: NetworkChangeMonitor? = null
    private val recovering = AtomicBoolean(false)

    fun startMonitoring(context: Context) {
        if (!shouldMonitor()) return
        synchronized(this) {
            if (monitor != null) return
            val appContext = context.applicationContext
            monitor = NetworkChangeMonitor(appContext, useUnderlyingNetwork = true) {
                scheduleRecovery(appContext)
            }.also { it.start() }
            LogUtil.i(AppConfig.TAG, "NetworkRecovery: monitoring started")
        }
    }

    fun stopMonitoring() {
        synchronized(this) {
            monitor?.stop()
            monitor = null
            recovering.set(false)
            LogUtil.i(AppConfig.TAG, "NetworkRecovery: monitoring stopped")
        }
    }

    private fun shouldMonitor(): Boolean =
        RstaBypassConfigInjector.isBypassEnabled() && SettingsManager.isVpnMode()

    private fun scheduleRecovery(context: Context) {
        if (!shouldMonitor() || !CoreServiceManager.isRunning()) return
        if (!recovering.compareAndSet(false, true)) return
        AngApplication.appScope.launch(Dispatchers.IO) {
            try {
                recover(context)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkRecovery: failed", e)
            } finally {
                recovering.set(false)
            }
        }
    }

    private suspend fun recover(context: Context) {
        RstaProxyServiceState.appendLog("[network] connectivity changed — restarting SNI bypass…")
        LogUtil.i(AppConfig.TAG, "NetworkRecovery: restarting SNI bypass and VPN stack")

        RstaVpnBootstrap.stopBypass(context)
        delay(600)

        val bootstrap = RstaVpnBootstrap.ensureBypassRunning(context)
        if (bootstrap.isFailure) {
            val msg = bootstrap.exceptionOrNull()?.message ?: "SNI bypass restart failed"
            RstaProxyServiceState.appendLog("[network] Error: $msg")
            LogUtil.e(AppConfig.TAG, "NetworkRecovery: $msg")
            return
        }
        ProxyForegroundService.detachNotificationForUnifiedVpn()
        delay(300)

        withContext(Dispatchers.Main) {
            CoreServiceManager.reloadTunnelAndCoreForNetworkChange()
        }
        RstaProxyServiceState.appendLog("[network] SNI bypass restarted")
    }
}
