package com.sniray.app.v2ray.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sniray.app.v2ray.AppConfig
import com.sniray.app.v2ray.core.CoreServiceManager
import com.sniray.app.v2ray.handler.MmkvManager
import com.sniray.app.v2ray.handler.SubscriptionUpdater
import com.sniray.app.v2ray.util.LogUtil

class BootReceiver : BroadcastReceiver() {
    /**
     * This method is called when the BroadcastReceiver is receiving an Intent broadcast.
     * It checks if the context is not null and the action is ACTION_BOOT_COMPLETED.
     * If the conditions are met, it starts the V2Ray service.
     *
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        LogUtil.i(AppConfig.TAG, "BootReceiver received: ${intent?.action}")

        if (context == null || intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            LogUtil.w(AppConfig.TAG, "BootReceiver: Invalid context or action")
            return
        }

        if (!MmkvManager.decodeStartOnBoot()) {
            LogUtil.i(AppConfig.TAG, "BootReceiver: Auto-start on boot is disabled")
            return
        }

        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            LogUtil.w(AppConfig.TAG, "BootReceiver: No server selected")
            return
        }

        LogUtil.i(AppConfig.TAG, "BootReceiver: Starting V2Ray service")
        CoreServiceManager.startVService(context)
        SubscriptionUpdater.sync(context)
    }
}
