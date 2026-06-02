package com.sniray.app.v2ray.dto

import java.io.Serializable

data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val useSniChain: Boolean = false,
) : Serializable

