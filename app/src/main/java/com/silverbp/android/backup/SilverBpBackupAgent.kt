package com.silverbp.android.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

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
 */
class SilverBpBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        Log.i(TAG, "System backup skipped; use SilverBP encrypted Drive backup for health data.")
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
