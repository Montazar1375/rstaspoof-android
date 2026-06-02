package com.sniray.app.service

import com.sniray.app.data.ProxyConfigEntity

sealed class ProxyRunState {
    data object Idle : ProxyRunState()
    data object Starting : ProxyRunState()
    data class Running(val config: ProxyConfigEntity) : ProxyRunState()
    data class Error(val message: String) : ProxyRunState()
}
