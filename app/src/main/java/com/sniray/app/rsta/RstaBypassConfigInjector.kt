package com.sniray.app.rsta

import com.sniray.app.data.ProxyConfigEntity
import com.sniray.app.v2ray.AppConfig
import com.sniray.app.v2ray.dto.entities.ProfileItem
import com.tencent.mmkv.MMKV

/**
 * Rewrites the v2ray server endpoint to loopback so Xray dials rstaspoof (raw TCP SNI bypass),
 * not a SOCKS proxy on that port.
 */
object RstaBypassConfigInjector {

    const val PREF_BYPASS_ENABLED = "rsta_bypass_enabled"
    const val PREF_ACTIVE_BYPASS_CONFIG_ID = "rsta_active_bypass_config_id"

    private const val KEY_ORIG_SERVER_PREFIX = "rsta_orig_server_"
    private const val KEY_ORIG_PORT_PREFIX = "rsta_orig_port_"

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    fun isBypassEnabled(): Boolean = mmkv.decodeBool(PREF_BYPASS_ENABLED, true)

    fun setBypassEnabled(enabled: Boolean) {
        mmkv.encode(PREF_BYPASS_ENABLED, enabled)
    }

    fun setActiveBypassConfigId(id: Long) {
        mmkv.encode(PREF_ACTIVE_BYPASS_CONFIG_ID, id)
    }

    fun getActiveBypassConfigId(): Long =
        mmkv.decodeLong(PREF_ACTIVE_BYPASS_CONFIG_ID, -1L)

    fun stashOriginalEndpoint(guid: String, profile: ProfileItem) {
        val server = profile.server.orEmpty()
        val port = profile.serverPort.orEmpty()
        if (server.isEmpty() && port.isEmpty()) return
        if (server == AppConfig.LOOPBACK) return
        mmkv.encode(KEY_ORIG_SERVER_PREFIX + guid, server)
        mmkv.encode(KEY_ORIG_PORT_PREFIX + guid, port)
    }

    fun restoreOriginalEndpoint(guid: String, profile: ProfileItem): ProfileItem {
        val origServer = mmkv.decodeString(KEY_ORIG_SERVER_PREFIX + guid)
        val origPort = mmkv.decodeString(KEY_ORIG_PORT_PREFIX + guid)
        if (!origServer.isNullOrEmpty()) profile.server = origServer
        if (!origPort.isNullOrEmpty()) profile.serverPort = origPort
        return profile
    }

    fun applyBypass(profile: ProfileItem, bypass: ProxyConfigEntity): ProfileItem {
        if (!isBypassEnabled()) return profile
        stashOriginalEndpoint("", profile) // guid filled by caller when available
        profile.server = AppConfig.LOOPBACK
        profile.serverPort = bypass.listenPort.toString()
        return profile
    }

    fun applyBypass(profile: ProfileItem, bypass: ProxyConfigEntity, guid: String): ProfileItem {
        if (!isBypassEnabled()) return restoreOriginalEndpoint(guid, profile)
        stashOriginalEndpoint(guid, profile)
        profile.server = AppConfig.LOOPBACK
        profile.serverPort = bypass.listenPort.toString()
        return profile
    }

    fun getEffectiveServerAddress(profile: ProfileItem, guid: String, bypass: ProxyConfigEntity?): String {
        if (!isBypassEnabled() || bypass == null) {
            return profile.server.orEmpty()
        }
        stashOriginalEndpoint(guid, profile)
        return AppConfig.LOOPBACK
    }

    fun getEffectiveServerPort(profile: ProfileItem, guid: String, bypass: ProxyConfigEntity?): Int {
        if (!isBypassEnabled() || bypass == null) {
            return profile.serverPort.orEmpty().toIntOrNull() ?: 0
        }
        return bypass.listenPort
    }
}
