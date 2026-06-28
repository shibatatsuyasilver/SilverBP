package com.silverbp.android.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * 自訂 Backup Agent — 系統 Auto Backup 不備份健康資料.
 *
 * **為什麼需要這個 agent?**
 * `data_extraction_rules.xml` / `backup_rules.xml` 已排除 Room DB、DataStore、
 * photos 與模型快取;但 app 宣告了 `backupAgent` 時,[onFullBackup] 會接管全量
 * 備份列舉。若這裡再呼叫 [fullBackupFile],就會繞過 XML 排除規則。
 *
 * SilverBP 的健康資料備份路徑是使用者手動啟用的端對端加密 Drive 快照;系統
 * cloud backup / transfer 不複製 DB 或 DataStore,讓隱私政策「健康資料只透過
 * 使用者啟用的 Drive 備份離開裝置」維持真實。
 *
 * 還原方向同樣設防: [onRestoreFile] 對健康敏感檔案(Room DB、健康相關
 * DataStore、Keystore 加密的 marker 偏好) explicit 跳過、不呼叫 super,避免
 * 某個舊版本 / OEM transfer 留下的資料集把陳舊健康資料或失效的 ciphertext
 * 覆寫回現有(或全新安裝)的狀態。
 */
class SilverBpBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        Log.i(TAG, "System backup skipped; use SilverBP encrypted Drive backup for health data.")
    }

    /**
     * Full backup 還原: 健康敏感檔案 explicit 跳過、不呼叫 super,其餘走預設
     * 行為(Auto Backup framework 已知道怎麼把檔案放回原本路徑).
     *
     * 跳過的目的地由 [isHealthSensitive] 判定:
     *  - Room DB silverbp.db 及其 -wal / -shm / -journal sidecar
     *  - 健康相關 DataStore 偏好(user_settings / current_member /
     *    achievements_internal / weight_sync_internal)
     *  - Keystore 加密的 marker 偏好(silverbp.dbkey / silverbp.sync.rootkeys /
     *    silverbp.backup.recovery);其 master key 解除安裝時就清掉,還原拿回的
     *    ciphertext 無法解密.
     *
     * 跳過時不寫回 destination(保留現有 / 全新安裝的狀態),但仍要把這個
     * entry 的 bytes 從串流讀掉,否則後續檔案的 tar 框架會錯位.
     */
    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File,
        type: Int,
        mode: Long,
        mtime: Long,
    ) {
        if (isHealthSensitive(destination)) {
            Log.i(TAG, "System restore skipped for sensitive file: ${destination.name}")
            discardRestoreStream(data, size)
            return
        }
        super.onRestoreFile(data, size, destination, type, mode, mtime)
    }

    /**
     * 判定 [destination] 是否為不接受系統還原的健康敏感檔案. 比對檔名即可 ——
     * 這些名稱在 app 私有資料夾內各自唯一.
     */
    private fun isHealthSensitive(destination: File): Boolean {
        val name = destination.name
        // Room DB + SQLCipher sidecar(-wal / -shm / -journal).
        if (name == DB_NAME || name.startsWith("$DB_NAME-")) return true
        // 健康 / 設定相關 DataStore 偏好.
        if (name in SENSITIVE_DATASTORE_FILES) return true
        // Keystore 加密的 marker 偏好(EncryptedSharedPreferences, 檔名帶 .xml).
        if (name in SENSITIVE_KEYSTORE_PREFS) return true
        return false
    }

    /**
     * 把這個 restore entry 的 [size] bytes 從串流讀掉但不落地任何檔案. 等同
     * super(`FullBackup.restoreFile`)的讀取行為,只是丟棄而非寫回 ——
     * 維持後續 tar entry 的框架不錯位. 不關閉 fd(由 framework 擁有/關閉).
     */
    private fun discardRestoreStream(data: ParcelFileDescriptor, size: Long) {
        if (size <= 0L) return
        val input = FileInputStream(data.fileDescriptor)
        val buffer = ByteArray(32 * 1024)
        var remaining = size
        while (remaining > 0L) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) break
            remaining -= read.toLong()
        }
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

        /** Room DB file name; -wal / -shm / -journal sidecar share this prefix. */
        private const val DB_NAME = "silverbp.db"

        /** DataStore preference files holding settings / health-related state. */
        private val SENSITIVE_DATASTORE_FILES = setOf(
            "user_settings.preferences_pb",
            "current_member.preferences_pb",
            "achievements_internal.preferences_pb",
            "weight_sync_internal.preferences_pb",
        )

        /**
         * Keystore-wrapped marker prefs (EncryptedSharedPreferences). Their master
         * key is cleared on uninstall, so restored ciphertext is undecryptable.
         */
        private val SENSITIVE_KEYSTORE_PREFS = setOf(
            "silverbp.dbkey.xml",
            "silverbp.sync.rootkeys.xml",
            "silverbp.backup.recovery.xml",
        )
    }
}
