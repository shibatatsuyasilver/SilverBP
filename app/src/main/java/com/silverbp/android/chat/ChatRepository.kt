package com.silverbp.android.chat

import com.silverbp.android.core.db.ChatDao
import com.silverbp.android.core.db.ChatMessageEntity
import com.silverbp.android.core.db.ChatSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: Role,
    val text: String,
    val imagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class Role { User, Assistant }
}

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

class ChatRepository(private val dao: ChatDao) {

    fun observeSessions(): Flow<List<ChatSession>> =
        dao.observeSessions().mapList { it.toDomain() }

    suspend fun upsertSession(session: ChatSession) =
        dao.upsertSession(session.toEntity())

    suspend fun deleteSession(id: String) = dao.deleteSession(id)

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(sessionId).mapList { it.toDomain() }

    suspend fun insertMessage(msg: ChatMessage) = dao.insertMessage(msg.toEntity())

    suspend fun deleteMessages(sessionId: String) = dao.deleteMessages(sessionId)
}

// --- Mappers ---

private fun ChatSessionEntity.toDomain() = ChatSession(
    id = id, title = title, createdAt = createdAt, updatedAt = updatedAt,
)

private fun ChatSession.toEntity() = ChatSessionEntity(
    id = id, title = title, createdAt = createdAt, updatedAt = updatedAt,
)

private fun ChatMessageEntity.toDomain() = ChatMessage(
    id = id,
    sessionId = sessionId,
    role = if (role == "user") ChatMessage.Role.User else ChatMessage.Role.Assistant,
    text = text,
    imagePath = imagePath,
    createdAt = createdAt,
)

private fun ChatMessage.toEntity() = ChatMessageEntity(
    id = id,
    sessionId = sessionId,
    role = if (role == ChatMessage.Role.User) "user" else "assistant",
    text = text,
    imagePath = imagePath,
    createdAt = createdAt,
)

private fun <T, R> Flow<List<T>>.mapList(transform: (T) -> R): Flow<List<R>> =
    map { list -> list.map(transform) }
