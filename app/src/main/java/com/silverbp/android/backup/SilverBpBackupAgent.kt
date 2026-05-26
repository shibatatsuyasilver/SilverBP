package com.silverbp.android.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import com.silverbp.android.security.DbKeyStore
import java.io.File

/**
 * 自訂 Backup Agent — 處理 SQLCipher 開啟時的 DB 排除邏輯.
 *
 * **為什麼需要這個 agent?**
 * Auto Backup 的 XML 規則(`data_extraction_rules.xml`) 是靜態的,但 SQLCipher
 * 是 runtime 切換的選項(`UserSettings.appLockEnabled`). 當使用者開啟 app-lock
 * 加密 DB 後,silverbp.db 是 SQLCipher ciphertext;Auto Backup 把 ciphertext
 * 傳到 Google Drive 沒用 — 解密用的 Keystore key 在解除安裝時會被清掉,還原
 * 後拿回的 ciphertext 是無法解密的廢檔.
 *
 * 解法: 在 [onFullBackup] 動態檢查 [DbKeyStore.isDbEncrypted];為 true 時跳過
 * 所有 DB 檔. 一次性 UI 提示由 Settings 畫面負責(看到 `appLockEnabled=true`
 * 時就告訴使用者要改用「匯出加密快照」).
 *
 * 註: 覆寫 [onFullBackup] 表示完全接管 — XML 規則不再生效. 因此這裡要 explicit
 * 把 DataStore 加進來. Key-Value backup 路徑([onBackup] / [onRestore]) 留空,
 * 走 Full Backup.
 */
class SilverBpBackupAgent : BackupAgent() {

    /**
     * 全量備份: 手動 enumerate 要備份的檔案,動態檢查 SQLCipher 狀態.
     *
     * 包含:
     *  - filesDir/datastore/user_settings.preferences_pb (DataStore)
     *  - silverbp.db / -wal / -shm (僅在 SQLCipher 未啟用時)
     *
     * 排除(由 [onFullBackup] 不呼叫 [fullBackupFile] 達成):
     *  - `filesDir/models/` (LLM weights, 可重新下載)
     *  - `filesDir/photos/` (依備份計畫不入備份)
     *  - `cacheDir` 內所有檔案 (transient)
     *  - SQLCipher 加密的 DB
     */
    override fun onFullBackup(data: FullBackupDataOutput) {
        val isDbEncrypted = runCatching {
            DbKeyStore.create(applicationContext).isDbEncrypted()
        }.getOrDefault(false)

        // DataStore — 永遠備份(其內的敏感欄位若有 Keystore 加密,還原後雖然
        // 拿回 ciphertext 也只是個 base64 字串而已 — 不會 crash,使用者重設
        // 即可. 比整個檔案不還原好.)
        val datastoreFile = File(filesDir, "datastore/user_settings.preferences_pb")
        if (datastoreFile.exists()) {
            try {
                fullBackupFile(datastoreFile, data)
            } catch (t: Throwable) {
                Log.w(TAG, "DataStore backup failed: $t")
            }
        }

        if (isDbEncrypted) {
            Log.i(TAG, "SQLCipher 啟用 — Auto Backup 不送 silverbp.db.")
            return
        }

        // 走非加密 DB 路徑.
        val dbDir = File(applicationInfo.dataDir, "databases")
        listOf("silverbp.db", "silverbp.db-wal", "silverbp.db-shm").forEach { name ->
            val file = File(dbDir, name)
            if (file.exists()) {
                try {
                    fullBackupFile(file, data)
                } catch (t: Throwable) {
                    Log.w(TAG, "DB file backup failed ($name): $t")
                }
            }
        }
    }

    /**
     * Full backup 還原: 走預設行為(Auto Backup framework 已知道怎麼把檔案
     * 放回原本路徑). 沒額外 transform.
     */
    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File,
        type: Int,
        mode: Long,
        mtime: Long,
    ) {
        super.onRestoreFile(data, size, destination, type, mode, mtime)
    }

    // Key-Value backup 路徑沒在用 — 但 BackupAgent 必須實作這兩個方法. 留空.
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) {
        // 走 Full Backup;K/V 路徑不使用.
    }

    override fun onRestore(
        data: BackupDataInput,
        appVersionCode: Int,
        newState: ParcelFileDescriptor,
    ) {
        // 走 Full Backup 還原.
    }

    companion object {
        private const val TAG = "SilverBpBackupAgent"
    }
}
