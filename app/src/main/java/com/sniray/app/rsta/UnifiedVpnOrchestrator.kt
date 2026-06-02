package com.sniray.app.rsta

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.sniray.app.data.AppDatabase
import com.sniray.app.data.ProxyConfigEntity
import com.sniray.app.v2ray.core.CoreServiceManager
import com.sniray.app.v2ray.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UnifiedVpnOrchestrator {

    suspend fun prepareVpnIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    suspend fun start(
        context: Context,
        bypassConfig: ProxyConfigEntity,
        v2rayGuid: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val guid = v2rayGuid?.takeIf { it.isNotEmpty() }
            ?: MmkvManager.getSelectServer().orEmpty()
        if (guid.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No v2ray server selected"))
        }

        RstaBypassConfigInjector.setActiveBypassConfigId(bypassConfig.id)

        if (RstaBypassConfigInjector.isBypassEnabled()) {
            val bootstrap = RstaVpnBootstrap.ensureBypassRunning(context)
            if (bootstrap.isFailure) {
                stop(context)
                return@withContext Result.failure(
                    bootstrap.exceptionOrNull() ?: IllegalStateException("SNI bypass failed"),
                )
            }
        }

        try {
            CoreServiceManager.startVService(context, guid)
        } catch (e: Exception) {
            stop(context)
            return@withContext Result.failure(e)
        }
        Result.success(Unit)
    }

    fun stop(context: Context) {
        try {
            CoreServiceManager.stopVService(context)
        } catch (_: Exception) {
        }
    }

    fun isVpnRunning(): Boolean = CoreServiceManager.isRunning()

    suspend fun loadBypassConfig(context: Context, configId: Long): ProxyConfigEntity? {
        return AppDatabase.get(context).proxyConfigDao().getById(configId)
    }
}
