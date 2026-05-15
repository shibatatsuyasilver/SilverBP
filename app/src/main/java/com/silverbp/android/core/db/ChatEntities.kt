package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chat_message",
    foreignKeys = [ForeignKey(
        entity = ChatSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** "user" or "assistant" */
    val role: String,
    val text: String,
    /** Filename inside cacheDir, null for text-only turns. */
    val imagePath: String?,
    val createdAt: Long,
)
