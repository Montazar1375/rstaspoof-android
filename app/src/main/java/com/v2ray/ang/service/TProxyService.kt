package com.v2ray.ang.service

/**
 * JNI entry point for [libhev-socks5-tunnel.so].
 *
 * The native library registers methods on this exact class name. Prebuilt binaries from
 * v2rayNG use `com.v2ray.ang.service`; local builds should use the same [PKGNAME] in
 * [scripts/build-hevtun.sh]. Application code lives in
 * [com.sniray.app.v2ray.service.TProxyService].
 */
class TProxyService private constructor() {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic
        external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        external fun TProxyStopService()

        @JvmStatic
        external fun TProxyGetStats(): LongArray?
    }
}
