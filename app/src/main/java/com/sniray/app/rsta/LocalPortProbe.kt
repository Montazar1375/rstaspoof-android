package com.sniray.app.rsta

import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.Socket

object LocalPortProbe {

    fun isOpen(port: Int, host: String = "127.0.0.1", connectTimeoutMs: Int = 500): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun awaitOpen(
        port: Int,
        timeoutMs: Long = 20_000,
        pollMs: Long = 200,
        host: String = "127.0.0.1",
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isOpen(port, host)) return true
            delay(pollMs)
        }
        return isOpen(port, host)
    }
}
