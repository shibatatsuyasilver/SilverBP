package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_session ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestSession(): ChatSessionEntity?

    @Query("SELECT * FROM chat_session WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    /**
     * Sessions ordered most-recent first, each joined with its latest message
     * for a 1-line preview. Correlated subquery picks the message with the
     * largest createdAt per session; null when the session is empty.
     */
    @Query(
        """
        SELECT s.id AS id,
               s.title AS title,
               s.createdAt AS createdAt,
               s.updatedAt AS updatedAt,
               (SELECT m.text FROM chat_message m
                  WHERE m.sessionId = s.id
                  ORDER BY m.createdAt DESC LIMIT 1) AS lastText,
               (SELECT m.role FROM chat_message m
                  WHERE m.sessionId = s.id
                  ORDER BY m.createdAt DESC LIMIT 1) AS lastRole,
               (SELECT m.createdAt FROM chat_message m
                  WHERE m.sessionId = s.id
                  ORDER BY m.createdAt DESC LIMIT 1) AS lastCreatedAt
        FROM chat_session s
        ORDER BY s.updatedAt DESC
        """
    )
    fun observeAllSessions(): Flow<List<SessionWithLastMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ChatSessionEntity)

    @Query("UPDATE chat_session SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: String, updatedAt: Long)

    @Query("UPDATE chat_session SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("DELETE FROM chat_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    /**
     * Image paths attached to a session's messages. Caller invokes this before
     * [deleteSession] so the cached image files can be removed from disk; the
     * CASCADE drops the rows themselves.
     */
    @Query("SELECT imagePath FROM chat_message WHERE sessionId = :sessionId AND imagePath IS NOT NULL")
    suspend fun imagePathsFor(sessionId: String): List<String>

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun messagesFor(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: ChatMessageEntity)

    @Query("UPDATE chat_message SET text = :text WHERE id = :id")
    suspend fun updateText(id: String, text: String)

    @Query("DELETE FROM chat_message WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
