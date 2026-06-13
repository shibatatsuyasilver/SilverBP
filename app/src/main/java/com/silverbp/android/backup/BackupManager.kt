package com.silverbp.android.backup

import android.util.Log
import androidx.room.withTransaction
import com.silverbp.android.R
import com.silverbp.android.core.db.SilverBpDatabase
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.sync.CombinedRoomSyncSink
import com.silverbp.android.sync.CombinedRoomSyncSource
import com.silverbp.android.sync.SettingsKvSyncMapper
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Orchestrates `.sbpbk` export / import. Reuses every existing sync component:
 *  - [CombinedRoomSyncSource.snapshotAll] 提供所有 Room 表 + tombstones 的 record stream.
 *  - [SettingsKvSyncMapper.snapshotRecords] 提供 DataStore 設定.
 *  - [CombinedRoomSyncSink.apply] 在匯入時把 record 寫回 — 走既有 LWW 機制.
 *  - [HlcClock.observe] 在匯入每筆 record 後把高水位線往前推,讓後續本地寫入嚴格遞增.
 *
 * 進度由 [exportPhase] / [importPhase] 兩個 StateFlow 暴露給 UI.
 */
class BackupManager(
    private val database: SilverBpDatabase,
    private val sourceFactory: () -> CombinedRoomSyncSource,
    private val sinkFactory: () -> CombinedRoomSyncSink,
    private val settingsKvMapper: SettingsKvSyncMapper,
    private val hlcClock: HlcClock,
    private val localNodeIdHex: String,
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val schemaVersion: Int,
    /**
     * Ensures an owner member exists and returns its id. Wired to
     * [com.silverbp.android.core.member.MemberRepository.ownerId], which
     * synthesises an owner if the table is empty. Called on import of a pre-v18
     * (member-less) backup so the owner is present before BP/medication records
     * resolve their absent memberId to it. Defaults to a no-op for the legacy
     * test constructor.
     */
    private val ensureOwnerId: suspend () -> String = { "" },
    /**
     * Drops [com.silverbp.android.core.member.MemberRepository]'s memoized owner
     * id. A Replace-mode restore runs `DELETE FROM member` and then re-creates
     * the owner from the backup's MEMBER records (whose owner id may differ from
     * the live one on a cross-device restore). The cache, warmed on cold start by
     * the anomaly watcher, would otherwise keep returning the now-deleted id and
     * strand every owner-scoped read on a phantom member (findings 1 & 4). Called
     * inside the import transaction right after [clearSyncTables]. No-op default
     * for the legacy test constructor.
     */
    private val invalidateOwnerCache: () -> Unit = {},
) {

    sealed class Phase {
        object Idle : Phase()
        data class Collecting(val recordCount: Int) : Phase()
        data class Encoding(val progress: Float) : Phase()
        object Encrypting : Phase()
        object Writing : Phase()
        /**
         * [recordCount] = records successfully applied. On import [skippedCount]
         * is how many records the sink rejected (logged + skipped) so the UI can
         * warn the user instead of implying a clean restore. 0 on export.
         */
        data class Success(
            val byteCount: Long,
            val recordCount: Int,
            val skippedCount: Int = 0,
        ) : Phase()
        data class Failure(val error: Throwable) : Phase()
    }

    data class ExportOptions(
        /** 預設打勾;使用者可在匯出畫面取消. */
        val includeChat: Boolean = true,
        /**
         * 是否同時寫入 Keystore 包裝 — 預設 true,提供同裝置免輸恢復碼還原.
         * 進階使用者可關掉以縮小檔頭(極小差別).
         */
        val includeKeystoreWrap: Boolean = true,
    )

    enum class ImportMode {
        /** 預設: 透過 LWW 與本地資料合併. 適合多裝置場景. */
        Merge,
        /** 進階: 匯入前先清空所有 sync 表. 適合「還原備份到全新狀態」. */
        Replace,
    }

    private val _exportPhase = MutableStateFlow<Phase>(Phase.Idle)
    val exportPhase: StateFlow<Phase> = _exportPhase.asStateFlow()

    private val _importPhase = MutableStateFlow<Phase>(Phase.Idle)
    val importPhase: StateFlow<Phase> = _importPhase.asStateFlow()

    // ============================================================
    // EXPORT
    // ============================================================

    /**
     * 匯出加密 `.sbpbk` 到 [out]. **不會關閉 stream** — caller 負責.
     *
     * @throws IllegalArgumentException [passphrase] 不是合法的 Crockford Base32 恢復碼.
     */
    suspend fun export(
        out: OutputStream,
        passphrase: String,
        options: ExportOptions = ExportOptions(),
    ) {
        try {
            _exportPhase.value = Phase.Collecting(0)

            // 1. 抽 snapshot.
            val source = sourceFactory()
            val records = ArrayList<SyncRecord>()
            records += source.snapshotAll(includeChat = options.includeChat)
            // 過濾掉 chat record(若使用者取消勾選 — 雙重保險,雖然 snapshotAll 已遵守 flag).
            if (!options.includeChat) {
                records.removeAll {
                    it.type == SyncEntityType.CHAT_SESSION || it.type == SyncEntityType.CHAT_MESSAGE
                }
            }
            // DataStore 設定走獨立 mapper.
            records += settingsKvMapper.snapshotRecords(hlcClock)

            _exportPhase.value = Phase.Collecting(records.size)

            // 2. 編碼明文載荷.
            _exportPhase.value = Phase.Encoding(0.5f)
            val manifest = BackupCodec.Manifest(
                // v2 = v18 家人成員格式(含 MEMBER record + memberId 欄位)。
                manifestVersion = 2,
                sourcePlatform = "android",
                schemaVersion = schemaVersion,
                hlcNodeIdHex = localNodeIdHex,
                includesChat = options.includeChat,
            )
            val plaintext = BackupCodec.encodePayload(manifest, records)
            _exportPhase.value = Phase.Encoding(1f)

            // 3. 推導 KEK / 產生 DEK / 雙重包裝.
            _exportPhase.value = Phase.Encrypting
            val entropy = RecoveryCode.decode(passphrase)
                ?: throw IllegalArgumentException(ServiceLocator.context.getString(R.string.backup_err_recovery_format))
            val kdfSalt = BackupCrypto.randomSalt()
            val kdfParams = BackupCrypto.KdfParams()
            val passphraseStr = encodeEntropyForKdf(entropy)
            val kek = BackupCrypto.deriveKekArgon2id(passphraseStr, kdfSalt, kdfParams)
            val dek = BackupCrypto.newDek()
            val recoveryWrap = BackupCrypto.wrapDek(dek, kek)
            val keystoreWrap = if (options.includeKeystoreWrap) {
                runCatching { BackupCrypto.wrapDekWithKeystore(dek) }
                    .onFailure { Log.w(TAG, "Keystore wrap unavailable: $it") }
                    .getOrNull()
            } else null

            // 4. 編碼 header(此時還不知 record_count 之後可調的 case,
            //    但 record_count 已知;header bytes 是 AEAD AAD,確定後不能變).
            val aeadNonce = BackupCrypto.randomNonce()
            val header = BackupCodec.Header(
                formatVersion = BackupContainer.FORMAT_VERSION,
                sourcePlatform = "android",
                sourceAppVer = "$appVersionName+$appVersionCode",
                schemaVersion = schemaVersion,
                contentVersion = 1,
                createdAtMs = System.currentTimeMillis(),
                payloadSize = plaintext.size.toLong(),
                kdfSalt = kdfSalt,
                kdfParams = kdfParams,
                aeadAlg = "AES-256-GCM",
                aeadNonce = aeadNonce,
                keystoreWrap = keystoreWrap?.let {
                    BackupCodec.KeystoreWrapRef(BackupCrypto.KEYSTORE_ALIAS, it)
                },
                recoveryWrap = BackupCodec.RecoveryWrapRef(recoveryWrap),
                recordCount = records.size,
                includesChat = options.includeChat,
            )
            val headerBytes = BackupCodec.encodeHeader(header)

            // 5. 加密 payload(把 header bytes 當 AAD,任何竄改都會讓 AEAD tag 認證失敗).
            val ciphertext = BackupCrypto.encryptPayload(plaintext, dek, aeadNonce, aad = headerBytes)

            // 6. 寫 container.
            _exportPhase.value = Phase.Writing
            BackupContainer.write(out, headerBytes, ciphertext)

            val totalBytes = BackupContainer.MAGIC.size + 2L + 2L + headerBytes.size + ciphertext.size
            _exportPhase.value = Phase.Success(byteCount = totalBytes, recordCount = records.size)
        } catch (t: Throwable) {
            Log.e(TAG, "export failed", t)
            _exportPhase.value = Phase.Failure(t)
            throw t
        }
    }

    // ============================================================
    // IMPORT
    // ============================================================

    /**
     * 從 [input] 匯入 `.sbpbk`. [passphrase] = null 表示先試裝置 Keystore;
     * 若 Keystore 包裝不在(跨裝置匯入)就必須提供恢復碼.
     */
    suspend fun import(
        input: InputStream,
        passphrase: String?,
        mode: ImportMode = ImportMode.Merge,
    ) {
        try {
            _importPhase.value = Phase.Collecting(0)

            // 1. 讀 container + header.
            val parsed = BackupContainer.read(input)
            val header = BackupCodec.decodeHeader(parsed.headerCbor)

            // 2. 解 DEK: 先試 Keystore,失敗回退到 recovery 路徑.
            val dek = unwrapDek(header, passphrase)
                ?: throw IOException(ServiceLocator.context.getString(R.string.backup_err_unwrap_key))

            // 3. 解密 payload(用原始 header bytes 當 AAD).
            _importPhase.value = Phase.Encrypting
            val plaintext = BackupCrypto.decryptPayload(
                parsed.payloadCiphertext, dek, header.aeadNonce, aad = parsed.headerCbor,
            )

            // 4. 解碼 payload(manifest + records).
            _importPhase.value = Phase.Encoding(0.5f)
            val (_, records) = BackupCodec.decodePayload(plaintext)
            // 完整性檢查:解出的筆數必須等於 header 宣告的筆數。AEAD 已經會擋掉
            // 截斷的密文,這層是對 codec/邏輯錯誤的縱深防禦,避免靜默少匯入。
            if (records.size != header.recordCount) {
                throw IOException(
                    ServiceLocator.context.getString(R.string.backup_err_incomplete, header.recordCount, records.size),
                )
            }
            _importPhase.value = Phase.Encoding(1f)

            // 5+6. 在「同一個」transaction 內 (Replace 模式) 清空 sync 表並套用每筆
            // record. SETTINGS_KV 走 settingsKvMapper,其他走 sink. 全包在一個
            // withTransaction 是資料安全的關鍵:若中途失敗或 coroutine 被取消,
            // Room 會整體回滾,不會留下「已清空但只匯入一半」的狀態.
            _importPhase.value = Phase.Writing
            val sink = sinkFactory()
            var appliedCount = 0
            var skippedCount = 0
            // 向後相容:pre-v18 備份沒有 MEMBER record。確保 owner 成員存在,讓
            // 無 memberId 的 BP/用藥列在 apply 時能解析成 owner(否則會落在
            // 任何 member-scoped 查詢都看不到的空 memberId 上)。Replace 模式下這
            // 要在 clearSyncTables 之後執行,所以放進同一個 transaction。
            val hasMemberRecords = records.any { it.type == SyncEntityType.MEMBER }
            database.withTransaction {
                if (mode == ImportMode.Replace) {
                    clearSyncTables()
                    // The owner row was just deleted; drop the memoized owner id so
                    // ensureOwnerId() / the MEMBER records below re-establish it
                    // instead of resolving to the stale (now-deleted) id. Done
                    // inside the transaction so a rollback leaves the cache in a
                    // state consistent with the rolled-back table.
                    invalidateOwnerCache()
                }
                if (!hasMemberRecords) {
                    ensureOwnerId()
                }
                for (record in records) {
                    try {
                        sink.apply(record)
                        hlcClock.observe(record.hlc) // 推進高水位線
                        appliedCount++
                    } catch (e: CancellationException) {
                        // 取消必須往外丟,讓 withTransaction 回滾;吞掉會把剩餘
                        // record 全當成 skipped,造成靜默資料遺失.
                        throw e
                    } catch (t: Throwable) {
                        skippedCount++
                        Log.w(TAG, "skip record ${record.type} pk=${record.pk}: $t")
                    }
                }
            }
            if (skippedCount > 0) {
                Log.w(TAG, "import applied $appliedCount, skipped $skippedCount of ${records.size}")
            }

            _importPhase.value = Phase.Success(
                byteCount = parsed.payloadCiphertext.size.toLong(),
                recordCount = appliedCount,
                skippedCount = skippedCount,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "import failed", t)
            _importPhase.value = Phase.Failure(t)
            throw t
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    /** 先試 Keystore,失敗(或沒有 keystore_wrap)時用 passphrase 路徑. */
    private fun unwrapDek(header: BackupCodec.Header, passphrase: String?): ByteArray? {
        header.keystoreWrap?.let { keystoreRef ->
            BackupCrypto.unwrapDekWithKeystore(keystoreRef.wrap)?.let { return it }
        }
        if (passphrase.isNullOrBlank()) return null
        val entropy = RecoveryCode.decode(passphrase) ?: return null
        val passphraseStr = encodeEntropyForKdf(entropy)
        val kek = BackupCrypto.deriveKekArgon2id(passphraseStr, header.kdfSalt, header.kdfParams)
        return BackupCrypto.unwrapDek(header.recoveryWrap.wrap, kek)
    }

    /**
     * 把 256-bit entropy 轉成一個穩定的字串給 Argon2id 當輸入.
     * 用 hex 表示;不論使用者輸入的時候大小寫或斷字怎樣,
     * [RecoveryCode.decode] 已經把它正規化為 32 byte entropy,所以 KEK 是固定的.
     */
    private fun encodeEntropyForKdf(entropy: ByteArray): String =
        entropy.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Replace 模式: 清空 24 個 sync 表 + tombstones.
     * 不碰 sync_device / sync_outbox(LAN 配對狀態) 與 user_settings DataStore.
     *
     * 呼叫端([import])已經在 Room withTransaction 內,這裡不再自己開
     * transaction — 這樣失敗/取消時清空與套用會一起回滾.
     */
    private fun clearSyncTables() {
        val db = database.openHelper.writableDatabase
        // CASCADE 會帶走子表(route_point, medication_schedule, coach_task,
        // set_log, chat_message, reading_tag),所以順序不嚴格但這樣寫比較清楚.
        db.execSQL("DELETE FROM reading_tag")
        db.execSQL("DELETE FROM tag")
        db.execSQL("DELETE FROM route_point")
        db.execSQL("DELETE FROM exercise_session")
        db.execSQL("DELETE FROM medication_schedule")
        db.execSQL("DELETE FROM medication_dose")
        db.execSQL("DELETE FROM medication")
        db.execSQL("DELETE FROM daily_step_log")
        db.execSQL("DELETE FROM achievement")
        db.execSQL("DELETE FROM coach_task")
        db.execSQL("DELETE FROM coach_plan")
        db.execSQL("DELETE FROM sleep_log")
        db.execSQL("DELETE FROM diet_check")
        db.execSQL("DELETE FROM food_log")
        db.execSQL("DELETE FROM set_log")
        db.execSQL("DELETE FROM strength_workout_session")
        db.execSQL("DELETE FROM exercise_catalog_item")
        db.execSQL("DELETE FROM bp_workout_association")
        db.execSQL("DELETE FROM chat_message")
        db.execSQL("DELETE FROM chat_session")
        db.execSQL("DELETE FROM bp_reading")
        // glucose_reading (v19) — cleared like any other sync table; restored from
        // the backup's GLUCOSE_READING records (a pre-v19 backup simply has none).
        db.execSQL("DELETE FROM glucose_reading")
        db.execSQL("DELETE FROM user_profile")
        // member (v18) — cleared like any other sync table; the owner is then
        // restored from the backup's MEMBER records, or synthesised by
        // [ensureOwnerId] when importing a pre-v18 (member-less) backup.
        db.execSQL("DELETE FROM member")
        db.execSQL("DELETE FROM tombstone")
    }

    companion object {
        private const val TAG = "BackupManager"
    }
}
