package com.silverbp.android.backup.auto

/**
 * 自動備份頻率 — 對應使用者在 BackupScreen 自動備份節區的 SegmentedButton 選項.
 * raw 字串會寫入 DataStore (key = `AUTO_BACKUP_FREQ`), 跨版本 forward-compat.
 *
 * Monthly 取 30 天 — WorkManager PeriodicWorkRequest 接受任意 Duration ≥ 15 分,
 * 沒有曆月概念, 用固定 30 天近似在使用情境上沒有意義差別.
 */
enum class AutoBackupFrequency(val raw: String) {
    Off("off"),
    Daily("daily"),
    Weekly("weekly"),
    Monthly("monthly");

    /** Periodic 任務的天數間隔; Off 沒有間隔. */
    val intervalDays: Long? get() = when (this) {
        Off -> null
        Daily -> 1L
        Weekly -> 7L
        Monthly -> 30L
    }

    companion object {
        fun fromRaw(raw: String?): AutoBackupFrequency =
            values().firstOrNull { it.raw == raw } ?: Off
    }
}
