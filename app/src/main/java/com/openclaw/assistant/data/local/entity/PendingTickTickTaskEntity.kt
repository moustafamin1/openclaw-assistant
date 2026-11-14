package com.openclaw.assistant.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_ticktick_tasks")
data class PendingTickTickTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val projectId: String = "inbox",
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = STATUS_PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_AUTH_ERROR = "AUTH_ERROR"
    }
}
