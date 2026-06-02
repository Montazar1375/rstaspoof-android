package com.sniray.app.v2ray.service

import android.content.Context
import com.sniray.app.rsta.RstaBypassConfigInjector
import com.sniray.app.rsta.RstaVpnBootstrap
import com.sniray.app.v2ray.core.CoreConfigManager
import com.sniray.app.v2ray.core.CoreNativeManager
import com.sniray.app.v2ray.dto.RealPingEvent
import com.sniray.app.v2ray.handler.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val useSniChain: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {},
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val bypassWasEnabled = if (useSniChain) {
            val wasEnabled = RstaBypassConfigInjector.isBypassEnabled()
            RstaBypassConfigInjector.setBypassEnabled(true)
            val bootstrap = runBlocking { RstaVpnBootstrap.ensureBypassRunning(context) }
            if (bootstrap.isFailure) {
                RstaBypassConfigInjector.setBypassEnabled(wasEnabled)
                onEvent(RealPingEvent.Finish("-1"))
                close()
                return
            }
            wasEnabled
        } else {
            RstaBypassConfigInjector.isBypassEnabled()
        }

        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = startRealPing(guid)
                    onEvent(RealPingEvent.Result(guid, result))
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    onEvent(RealPingEvent.Progress("$left / $count"))
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: CancellationException) {
                onEvent(RealPingEvent.Finish("-1"))
            } finally {
                if (useSniChain) {
                    RstaBypassConfigInjector.setBypassEnabled(bypassWasEnabled)
                }
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid, useSniChain)
        if (!configResult.status) {
            return retFailure
        }
        return CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
    }
}
