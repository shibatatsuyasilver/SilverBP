package com.silverbp.android.sync

import com.silverbp.android.settings.KvValue
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue

/**
 * 實作預留的 [SyncEntityType.SETTINGS_KV] (tag 64).
 *
 * DataStore preferences 不是 Room 表,所以這支 mapper 和其他 entity mapper 不一樣 —
 * 沒有 `SyncRecordMapper<TEntity>` 的 `encode(entity, hlc)` 形式,而是直接從
 * [UserSettingsRepository] 抽出整個 snapshot,把每個非預設鍵變成一筆 record.
 *
 * **每個 DataStore 鍵 → 一筆 SyncRecord** 的好處:
 *  - LWW gate 在 per-key 層級工作,將來若要擴到 LAN sync 不用改 wire 格式.
 *  - 跨平台對等: iOS UserDefaults 也以 per-key 結構暴露,鍵名對齊即可.
 *
 * Wire payload(每筆 SETTINGS_KV record):
 *   pk = DataStore 鍵字串(`Keys.GUIDELINE.name` 等)
 *   payload:
 *     1: type_tag  (uint, 0=bool, 1=int, 2=long, 3=float, 4=text)
 *     2: value     (對應的 SyncValue type)
 *
 * **敏感欄位**(Gemini API key、system prompt、chat persona、user nickname)
 * 透過 [UserSettingsRepository.snapshotKv] 已經以明文形式吐出 — AES-GCM 容器
 * 保護整個載荷的機密性. 匯入時透過 [UserSettingsRepository.applyKvSingle] 寫回,
 * 該 setter 會視目標裝置的 `appLockEnabled` 重新走 Keystore 加密包裝.
 */
class SettingsKvSyncMapper(
    private val settings: UserSettingsRepository,
) {
    private object Field {
        const val TYPE_TAG = 1
        const val VALUE = 2
    }

    private object TypeTag {
        const val BOOL = 0
        const val INT = 1
        const val LONG = 2
        const val FLOAT = 3
        const val TEXT = 4
    }

    /** 抓 DataStore snapshot,把每個非預設鍵編成一筆 SyncRecord. */
    suspend fun snapshotRecords(clock: HlcClock): List<SyncRecord> {
        val kv = settings.snapshotKv()
        return kv.map { (key, value) ->
            SyncRecord(
                type = SyncEntityType.SETTINGS_KV,
                pk = key,
                hlc = clock.next(),
                deletedAt = null,
                payload = encodeValue(value),
            )
        }
    }

    suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.SETTINGS_KV) {
            "SettingsKvSyncMapper applied to wrong entity type: ${record.type}"
        }
        if (record.isTombstone) {
            // 刪鍵 = 重設回預設. snapshotKv 是稀疏(只含非預設值),所以刪掉鍵
            // 後 UI 流會 fallback 到該欄位的預設.
            settings.removeKv(record.pk)
            return
        }
        val value = decodeValue(record.payload) ?: return
        settings.applyKvSingle(record.pk, value)
    }

    // ---- encode / decode ----

    private fun encodeValue(v: KvValue): Map<Int, SyncValue> = when (v) {
        is KvValue.B -> mapOf(
            Field.TYPE_TAG to SyncValue.Int64(TypeTag.BOOL.toLong()),
            Field.VALUE to SyncValue.Bool(v.value),
        )
        is KvValue.I -> mapOf(
            Field.TYPE_TAG to SyncValue.Int64(TypeTag.INT.toLong()),
            Field.VALUE to SyncValue.Int64(v.value.toLong()),
        )
        is KvValue.L -> mapOf(
            Field.TYPE_TAG to SyncValue.Int64(TypeTag.LONG.toLong()),
            Field.VALUE to SyncValue.Int64(v.value),
        )
        is KvValue.F -> mapOf(
            Field.TYPE_TAG to SyncValue.Int64(TypeTag.FLOAT.toLong()),
            Field.VALUE to SyncValue.Double(v.value.toDouble()),
        )
        is KvValue.T -> mapOf(
            Field.TYPE_TAG to SyncValue.Int64(TypeTag.TEXT.toLong()),
            Field.VALUE to SyncValue.Text(v.value),
        )
    }

    private fun decodeValue(p: Map<Int, SyncValue>): KvValue? {
        val tag = (p[Field.TYPE_TAG] as? SyncValue.Int64)?.value?.toInt() ?: return null
        return when (tag) {
            TypeTag.BOOL -> (p[Field.VALUE] as? SyncValue.Bool)?.let { KvValue.B(it.value) }
            TypeTag.INT -> (p[Field.VALUE] as? SyncValue.Int64)?.let { KvValue.I(it.value.toInt()) }
            TypeTag.LONG -> (p[Field.VALUE] as? SyncValue.Int64)?.let { KvValue.L(it.value) }
            TypeTag.FLOAT -> (p[Field.VALUE] as? SyncValue.Double)?.let { KvValue.F(it.value.toFloat()) }
            TypeTag.TEXT -> (p[Field.VALUE] as? SyncValue.Text)?.let { KvValue.T(it.value) }
            else -> null
        }
    }
}
