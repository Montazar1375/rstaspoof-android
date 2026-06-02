package com.sniray.app.service

import android.content.Context
import com.sniray.app.data.ProxyConfigEntity
import org.json.JSONObject
import java.io.File

object ProxyConfigFile {
    private const val RUN_CONFIG = "proxy_run.json"

    fun write(context: Context, config: ProxyConfigEntity): File {
        val json = JSONObject().apply {
            put("LISTEN_HOST", config.listenHost)
            put("LISTEN_PORT", config.listenPort)
            put("CONNECT_IP", config.connectHost)
            put("CONNECT_PORT", config.connectPort)
            put("FAKE_SNI", config.fakeSni)
            put("BYPASS_METHOD", config.method)
            put("MONITOR", true)
        }
        val file = File(context.filesDir, RUN_CONFIG)
        file.writeText(json.toString(2))
        return file
    }
}
