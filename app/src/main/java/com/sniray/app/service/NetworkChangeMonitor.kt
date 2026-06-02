package com.sniray.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

class NetworkChangeMonitor(
    context: Context,
    private val onNetworkChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null
    private var lastFingerprint: String? = null
    private var registered = false

    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRestart()
        override fun onLost(network: Network) = scheduleRestart()
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = scheduleRestart()
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = null
        connectivityManager.unregisterNetworkCallback(callback)
        registered = false
    }

    private fun scheduleRestart() {
        val fingerprint = networkFingerprint()
        if (fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint

        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = Runnable {
            pendingRestart = null
            onNetworkChanged()
        }
        handler.postDelayed(pendingRestart!!, RESTART_DELAY_MS)
    }

    private fun networkFingerprint(): String {
        val active = connectivityManager.activeNetwork ?: return "none"
        val caps = connectivityManager.getNetworkCapabilities(active)
        val transports = buildList {
            if (caps == null) return@buildList
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }
        return "${active.networkHandle}:${transports.joinToString("+").ifEmpty { "unknown" }}"
    }

    companion object {
        private const val RESTART_DELAY_MS = 1000L
    }
}
