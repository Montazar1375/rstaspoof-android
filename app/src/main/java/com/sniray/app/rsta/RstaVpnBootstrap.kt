package com.sniray.app.rsta

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sniray.app.service.ProxyForegroundService
import com.sniray.app.service.ProxyRunState
import com.sniray.app.v2ray.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Starts or stops the rstaspoof foreground service before/after the embedded v2ray VPN core.
 */
object RstaVpnBootstrap {

    private const val BOOTSTRAP_TIMEOUT_MS = 30_000L
    private const val POLL_MS = 200L

    suspend fun ensureBypassRunning(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (!RstaBypassConfigInjector.isBypassEnabled()) {
            return@withContext Result.success(Unit)
        }
        if (!SettingsManager.isVpnMode()) {
            return@withContext Result.success(Unit)
        }

        val bypass = RstaBypassHolder.loadBypassConfig(context)
            ?: return@withContext Result.failure(
                IllegalStateException("Select an SNI bypass profile on the Bypass tab"),
            )

        RstaBypassConfigInjector.setActiveBypassConfigId(bypass.id)

        val port = bypass.listenPort
        if (LocalPortProbe.isOpen(port)) {
            return@withContext Result.success(Unit)
        }

        try {
            ContextCompat.startForegroundService(
                context,
                ProxyForegroundService.startIntent(context, bypass.id),
            )
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("Could not start SNI bypass service: ${e.message}", e),
            )
        }

        // Allow onStartCommand + process spawn to run on the main thread.
        delay(150)

        val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (val state = RstaProxyServiceState.runState.value) {
                is ProxyRunState.Error -> {
                    return@withContext Result.failure(
                        IllegalStateException(state.message),
                    )
                }
                is ProxyRunState.Running -> {
                    if (LocalPortProbe.isOpen(port)) {
                        return@withContext Result.success(Unit)
                    }
                }
                else -> Unit
            }
            if (LocalPortProbe.isOpen(port)) {
                return@withContext Result.success(Unit)
            }
            delay(POLL_MS)
        }

        stopBypass(context)
        val hint = RstaProxyServiceState.lastLogLine.value
            ?: "Run ./scripts/build-android.sh, reinstall, then check the Logs tab."
        return@withContext Result.failure(
            IllegalStateException(
                "SNI bypass did not start on 127.0.0.1:$port. $hint",
            ),
        )
    }

    /** Stops the rstaspoof process if it is running (always runs; chain may already be toggled off). */
    fun stopBypass(context: Context) {
        context.startService(
            Intent(context, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_STOP
            },
        )
        RstaProxyServiceState.reset()
    }

    fun shouldBootstrapVpn(): Boolean =
        RstaBypassConfigInjector.isBypassEnabled() && SettingsManager.isVpnMode()
}
