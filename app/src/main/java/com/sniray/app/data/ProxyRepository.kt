package com.sniray.app.data

import kotlinx.coroutines.flow.Flow

class ProxyRepository(private val dao: ProxyConfigDao) {
    fun observeConfigs(): Flow<List<ProxyConfigEntity>> = dao.observeAll()

    suspend fun getById(id: Long): ProxyConfigEntity? = dao.getById(id)

    suspend fun save(config: ProxyConfigEntity): Long {
        return if (config.id == 0L) {
            dao.insert(config)
        } else {
            dao.update(config)
            config.id
        }
    }

    suspend fun delete(config: ProxyConfigEntity) = dao.delete(config)

    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())
}
