package com.sniray.app.rsta

import android.content.Context
import com.sniray.app.data.AppDatabase
import com.sniray.app.data.ProxyConfigEntity
import com.sniray.app.v2ray.dto.entities.ProfileItem
import kotlinx.coroutines.runBlocking

object RstaBypassHolder {

    fun effectiveProfile(context: Context, guid: String, profile: ProfileItem): ProfileItem {
        if (!RstaBypassConfigInjector.isBypassEnabled()) {
            return RstaBypassConfigInjector.restoreOriginalEndpoint(guid, profile)
        }
        val bypass = loadBypassConfig(context) ?: return profile
        return RstaBypassConfigInjector.applyBypass(profile, bypass, guid)
    }

    fun loadBypassConfig(context: Context): ProxyConfigEntity? {
        val id = RstaBypassConfigInjector.getActiveBypassConfigId()
        if (id < 0L) return null
        return runBlocking {
            AppDatabase.get(context).proxyConfigDao().getById(id)
        }
    }
}
