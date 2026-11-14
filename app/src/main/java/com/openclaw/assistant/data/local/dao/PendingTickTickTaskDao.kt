package com.openclaw.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.openclaw.assistant.data.local.entity.PendingTickTickTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingTickTickTaskDao {
    @Insert
    suspend fun insert(task: PendingTickTickTaskEntity): Long

    @Query("SELECT * FROM pending_ticktick_tasks ORDER BY createdAt ASC")
    suspend fun getAllOrdered(): List<PendingTickTickTaskEntity>

    @Query("SELECT * FROM pending_ticktick_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PendingTickTickTaskEntity>>

    @Query("SELECT COUNT(*) FROM pending_ticktick_tasks")
    fun observeCount(): Flow<Int>

    @Query("UPDATE pending_ticktick_tasks SET status = :status, attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, status: String, error: String?)

    @Query("UPDATE pending_ticktick_tasks SET status = 'PENDING', lastError = NULL WHERE status = 'AUTH_ERROR'")
    suspend fun resetAuthErrors()

    @Query("DELETE FROM pending_ticktick_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
