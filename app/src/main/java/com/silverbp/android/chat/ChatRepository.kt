package com.silverbp.android.chat

import com.silverbp.android.core.db.ChatDao
import com.silverbp.android.core.db.ChatMessageEntity
import com.silverbp.android.core.db.ChatSessionEntity
import com.silverbp.android.core.db.SessionWithLastMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Mirror of [com.silverbp.android.core.BpRepository] for chat sessions and
 * messages. Flow reads, suspend writes, mapping inside the repo.
 *
 * Multi-session schema. The drawer surfaces all sessions via [observeSessions];
 * [ensureActiveSession] is still used on cold start to pick the most-recently
 * touched session (or create one).
 */
class ChatRepository(private val dao: ChatDao) {

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(sessionId).map { rows -> rows.map { it.toDomain() } }

    suspend fun messagesFor(sessionId: String): List<ChatMessage> =
        dao.messagesFor(sessionId).map { it.toDomain() }

    fun observeSessions(): Flow<List<ChatSessionSummary>> =
        dao.observeAllSessions().map { rows -> rows.map { it.toSummary() } }

    suspend fun getSession(id: String): ChatSessionSummary? =
        dao.getSession(id)?.let {
            ChatSessionSummary(
                id = it.id,
                title = it.title,
                createdAt = Instant.ofEpochMilli(it.createdAt),
                updatedAt = Instant.ofEpochMilli(it.updatedAt),
                lastSnippet = null,
                lastRole = null,
                lastCreatedAt = null,
            )
        }

    suspend fun ensureActiveSession(): String {
        val existing = dao.latestSession()
        if (existing != null) return existing.id
        return newSession()
    }

    /** Always-create a new session. Returns its id. Title defaults to [DEFAULT_TITLE_NEW]. */
    suspend fun newSession(title: String = DEFAULT_TITLE_NEW): String {
        val now = Instant.now().toEpochMilli()
        val id = UUID.randomUUID().toString()
        dao.upsertSession(
            ChatSessionEntity(id = id, title = title, createdAt = now, updatedAt = now),
        )
        return id
    }

    suspend fun renameSession(id: String, title: String) {
        val now = Instant.now().toEpochMilli()
        dao.updateTitle(id, title, now)
    }

    /**
     * Delete a session and all its messages. Image files attached to those
     * messages are removed from the cache directory first; the CASCADE on
     * `chat_message.sessionId` then drops the rows.
     */
    suspend fun deleteSession(id: String) {
        val paths = dao.imagePathsFor(id)
        dao.deleteSession(id)
        if (paths.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (p in paths) {
                    runCatching { File(p).delete() }
                }
            }
        }
    }

    /** Returns the inserted message id so the caller can stream-update it later. */
    suspend fun appendUser(sessionId: String, text: String, imagePath: String?): String {
        val now = Instant.now().toEpochMilli()
        val id = UUID.randomUUID().toString()
        dao.insertMessage(
            ChatMessageEntity(
                id = id,
                sessionId = sessionId,
                role = ROLE_USER,
                text = text,
                imagePath = imagePath,
                createdAt = now,
            ),
        )
        dao.touchSession(sessionId, now)
        return id
    }

    /** Insert an empty assistant placeholder; caller will [updateAssistantText] as tokens arrive. */
    suspend fun appendAssistantPlaceholder(sessionId: String): String {
        val now = Instant.now().toEpochMilli()
        val id = UUID.randomUUID().toString()
        dao.insertMessage(
            ChatMessageEntity(
                id = id,
                sessionId = sessionId,
                role = ROLE_ASSISTANT,
                text = "",
                imagePath = null,
                createdAt = now,
            ),
        )
        dao.touchSession(sessionId, now)
        return id
    }

    suspend fun updateAssistantText(messageId: String, text: String) {
        dao.updateText(messageId, text)
    }

    suspend fun deleteMessage(messageId: String) = dao.deleteMessage(messageId)

    suspend fun clear(sessionId: String) = dao.clearSession(sessionId)

    private fun ChatMessageEntity.toDomain() = ChatMessage(
        id = id,
        role = ChatMessage.Role.fromRaw(role),
        text = text,
        imagePath = imagePath,
        createdAt = Instant.ofEpochMilli(createdAt),
    )

    private fun SessionWithLastMessage.toSummary() = ChatSessionSummary(
        id = id,
        title = title,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        lastSnippet = lastText?.takeIf { it.isNotBlank() },
        lastRole = lastRole?.let { ChatMessage.Role.fromRaw(it) },
        lastCreatedAt = lastCreatedAt?.let { Instant.ofEpochMilli(it) },
    )

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"

        /** Legacy default title from the single-session era. */
        const val DEFAULT_TITLE_LEGACY = "聊天"

        /** Title used by the drawer "新對話" button and by [newSession] until LLM auto-titling overrides it. */
        const val DEFAULT_TITLE_NEW = "新對話"

        /** Titles considered "untouched" — auto-title generation is allowed to overwrite these. */
        val DEFAULT_TITLES: Set<String> = setOf(DEFAULT_TITLE_LEGACY, DEFAULT_TITLE_NEW)
    }
}

/**
 * Domain projection of a chat session row joined with its latest message.
 * Used by the sessions drawer to render title + relative timestamp + 1-line
 * preview.
 */
data class ChatSessionSummary(
    val id: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastSnippet: String?,
    val lastRole: ChatMessage.Role?,
    val lastCreatedAt: Instant?,
)

/**
 * Domain-side message used by the UI and the chat-recognizer pipeline.
 * Lives in the chat package (not under `recognition.chat`) so it can be
 * referenced from both layers without a dependency cycle.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val imagePath: String? = null,
    val createdAt: Instant = Instant.now(),
) {
    enum class Role(val raw: String) {
        System(ChatRepository.ROLE_SYSTEM),
        User(ChatRepository.ROLE_USER),
        Assistant(ChatRepository.ROLE_ASSISTANT);

        companion object {
            fun fromRaw(s: String): Role = entries.firstOrNull { it.raw == s } ?: User
        }
    }
}
