package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_session", indices = [Index("updatedAt")])
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chat_message",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("createdAt")],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val imagePath: String?,
    val createdAt: Long,
)

/**
 * Projection used by the chat-sessions drawer — joins each session with the
 * latest message in that session for a 1-line preview. `lastText` / `lastRole`
 * are null when the session has no messages yet.
 */
data class SessionWithLastMessage(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastText: String?,
    val lastRole: String?,
    val lastCreatedAt: Long?,
)
