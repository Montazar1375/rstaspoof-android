package com.rstaspoof.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyConfigDao {
    @Query("SELECT * FROM proxy_configs ORDER BY lastUsedAt DESC, name ASC")
    fun observeAll(): Flow<List<ProxyConfigEntity>>

    @Query("SELECT COUNT(*) FROM proxy_configs")
    suspend fun count(): Int

    @Query("SELECT * FROM proxy_configs WHERE id = :id")
    suspend fun getById(id: Long): ProxyConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ProxyConfigEntity): Long

    @Update
    suspend fun update(config: ProxyConfigEntity)

    @Delete
    suspend fun delete(config: ProxyConfigEntity)

    @Query("UPDATE proxy_configs SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)
}
