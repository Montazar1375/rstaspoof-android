package com.sniray.app.v2ray.util

import android.content.Context
import com.sniray.app.R

object RstaBypassValidator {

    private val methods = setOf("fragment", "fake_sni", "combined")

    fun validate(
        context: Context,
        name: String,
        listenHost: String,
        listenPort: String,
        connectHost: String,
        connectPort: String,
        fakeSni: String,
        method: String,
    ): String? {
        if (name.isBlank()) return context.getString(R.string.rsta_validation_name_required)
        if (listenHost.isBlank()) return context.getString(R.string.rsta_validation_listen_host_required)
        if (connectHost.isBlank()) return context.getString(R.string.rsta_validation_connect_host_required)
        if (fakeSni.isBlank()) return context.getString(R.string.rsta_validation_fake_sni_required)
        if (method !in methods) return context.getString(R.string.rsta_validation_method_invalid)
        val lp = listenPort.toIntOrNull()
            ?: return context.getString(R.string.rsta_validation_listen_port_invalid)
        val cp = connectPort.toIntOrNull()
            ?: return context.getString(R.string.rsta_validation_connect_port_invalid)
        if (lp !in 1..65535 || cp !in 1..65535) {
            return context.getString(R.string.rsta_validation_port_range)
        }
        return null
    }
}
