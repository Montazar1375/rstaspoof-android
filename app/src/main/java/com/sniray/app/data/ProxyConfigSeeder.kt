package com.sniray.app.data

import android.content.Context
import com.sniray.app.rsta.RstaBypassConfigInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object ProxyConfigSeeder {

    private val seedMutex = Mutex()

    suspend fun seedIfEmpty(context: Context) = seedMutex.withLock {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(context).proxyConfigDao()
            if (dao.count() > 0) return@withContext
            val id = dao.insert(
                ProxyConfigEntity(
                    name = "Default",
                    listenHost = "0.0.0.0",
                    listenPort = 40443,
                    connectHost = "104.19.229.21",
                    connectPort = 443,
                    fakeSni = "www.hcaptcha.com",
                    method = "combined",
                ),
            )
            RstaBypassConfigInjector.setActiveBypassConfigId(id)
        }
    }
}
