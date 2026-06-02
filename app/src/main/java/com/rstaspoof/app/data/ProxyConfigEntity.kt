package com.rstaspoof.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_configs")
data class ProxyConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val listenHost: String,
    val listenPort: Int,
    val connectHost: String,
    val connectPort: Int,
    val fakeSni: String,
    val method: String,
    val lastUsedAt: Long = 0,
)
