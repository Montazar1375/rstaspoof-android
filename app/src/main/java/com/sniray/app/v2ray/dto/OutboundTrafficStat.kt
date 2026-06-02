package com.sniray.app.v2ray.dto

data class OutboundTrafficStat(
    val tag: String,
    val direction: String,
    val value: Long,
)