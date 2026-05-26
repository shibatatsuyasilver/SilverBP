package com.silverbp.android.sync

import com.silverbp.android.core.db.ChatDao
import com.silverbp.android.core.db.ChatMessageEntity
import com.silverbp.android.core.db.ChatSessionEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * 補上 [SyncEntityType.CHAT_SESSION] (tag 11) 與 [SyncEntityType.CHAT_MESSAGE]
 * (tag 12) 的映射. 這兩個 entity 的 Room schema 目前**沒有 `hlcUpdatedAt`
 * 欄位**(不像 BP/exercise/medication 經過 v7→v8 sync migration),所以這層
 * mapper 的設計取捨:
 *
 *  - **Encode**: 由 caller 傳入 [Hlc] — 通常是 [com.silverbp.android.sync.engine.HlcClock.next].
 *    Entity 內沒有持久的 HLC 可以回填,所以每次 encode 都是新鮮 HLC.
 *  - **Apply**: 走標準 `upsertSession` / `insertMessage`(`OnConflictStrategy.REPLACE`).
 *    HLC 不持久化 — 等於每次有新的 record 進來,直接覆寫. 對「備份還原」的場景
 *    這是正確語意:source-of-truth 是備份檔.
 *  - **Tombstone**: tag-11 session 刪除走 `deleteSession(pk)`(CASCADE 帶走 messages);
 *    tag-12 message 刪除走 `deleteMessage(pk)`.
 *
 * 圖檔(`imagePath` 欄位指向 `cacheDir/chat-images/`)**不**在備份內;欄位內容仍
 * encode/decode 完整路徑字串,但目標裝置上對應的檔案不會存在,UI 端 Coil 載入
 * 失敗會優雅退場.
 *
 * 與 iOS BPCoach 對等的 Swift 端尚未寫;先把 Android 兩支實作出來,iOS team
 * 接手時依此 wire 文件鏡像實作.
 */

/**
 * Wire field tags — chat_session payload:
 *   1: title          (string)
 *   2: createdAtMs    (int)
 *   3: updatedAtMs    (int)
 */
class ChatSessionSyncMapper(
    private val chatDao: ChatDao,
) : SyncRecordMapper<ChatSessionEntity> {

    private object Field {
        const val TITLE = 1
        const val CREATED_AT_MS = 2
        const val UPDATED_AT_MS = 3
    }

    override fun encode(entity: ChatSessionEntity, hlc: Hlc): SyncRecord {
        val payload = mapOf<Int, SyncValue>(
            Field.TITLE to SyncValue.Text(entity.title),
            Field.CREATED_AT_MS to SyncValue.Int64(entity.createdAt),
            Field.UPDATED_AT_MS to SyncValue.Int64(entity.updatedAt),
        )
        return SyncRecord(
            type = SyncEntityType.CHAT_SESSION,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.CHAT_SESSION) {
            "ChatSessionSyncMapper applied to wrong entity type: ${record.type}"
        }
        if (record.isTombstone) {
            chatDao.deleteSession(record.pk)
            return
        }
        val p = record.payload
        val entity = ChatSessionEntity(
            id = record.pk,
            title = (p[Field.TITLE] as? SyncValue.Text)?.value.orEmpty(),
            createdAt = (p[Field.CREATED_AT_MS] as? SyncValue.Int64)?.value ?: 0L,
            updatedAt = (p[Field.UPDATED_AT_MS] as? SyncValue.Int64)?.value ?: 0L,
        )
        chatDao.upsertSession(entity)
    }
}

/**
 * Wire field tags — chat_message payload:
 *   1: sessionId      (string UUID, FK to chat_session.id)
 *   2: role           (string, "user"|"assistant")
 *   3: text           (string)
 *   4: imagePath      (string?, local cache path — file itself not synced)
 *   5: createdAtMs    (int)
 */
class ChatMessageSyncMapper(
    private val chatDao: ChatDao,
) : SyncRecordMapper<ChatMessageEntity> {

    private object Field {
        const val SESSION_ID = 1
        const val ROLE = 2
        const val TEXT = 3
        const val IMAGE_PATH = 4
        const val CREATED_AT_MS = 5
    }

    override fun encode(entity: ChatMessageEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            Field.SESSION_ID to SyncValue.Text(entity.sessionId),
            Field.ROLE to SyncValue.Text(entity.role),
            Field.TEXT to SyncValue.Text(entity.text),
            Field.IMAGE_PATH to (entity.imagePath?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.CREATED_AT_MS to SyncValue.Int64(entity.createdAt),
        )
        return SyncRecord(
            type = SyncEntityType.CHAT_MESSAGE,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.CHAT_MESSAGE) {
            "ChatMessageSyncMapper applied to wrong entity type: ${record.type}"
        }
        if (record.isTombstone) {
            chatDao.deleteMessage(record.pk)
            return
        }
        val p = record.payload
        val entity = ChatMessageEntity(
            id = record.pk,
            sessionId = (p[Field.SESSION_ID] as? SyncValue.Text)?.value.orEmpty(),
            role = (p[Field.ROLE] as? SyncValue.Text)?.value.orEmpty(),
            text = (p[Field.TEXT] as? SyncValue.Text)?.value.orEmpty(),
            imagePath = (p[Field.IMAGE_PATH] as? SyncValue.Text)?.value,
            createdAt = (p[Field.CREATED_AT_MS] as? SyncValue.Int64)?.value ?: 0L,
        )
        // FK cascade: 若 parent session 還沒到,Room 會拒絕 insert. 在備份還原情境下,
        // 我們先 import session 再 import message(順序由 CombinedRoomSyncSource 控制).
        // 為了避免 race 把 silent-drop 處理掉,讓 Room 的 FK 錯誤往上拋 — caller 端的
        // CombinedRoomSyncSink 已經有 try/catch logging.
        chatDao.insertMessage(entity)
    }
}
