package com.silverbp.android.ui.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.backup.BackupManager
import com.silverbp.android.backup.RecoveryCode
import com.silverbp.android.backup.RecoveryCodeStore
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel 給三個 backup 畫面共用:
 *  - ExportBackupScreen / RecoveryCodeSetupScreen — 共享匯出流程與恢復碼生成
 *  - ImportBackupScreen — 匯入流程
 *  - ViewRecoveryCodeScreen — 顯示已存的恢復碼
 *
 * 進度透過 [BackupManager] 的 `exportPhase` / `importPhase` 直接暴露;
 * 這裡只負責 SAF URI → stream 的 wiring 與恢復碼生成/驗證.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val backupManager: BackupManager = ServiceLocator.backupManager
    private val recoveryStore: RecoveryCodeStore by lazy { RecoveryCodeStore.create(application) }

    val exportPhase: StateFlow<BackupManager.Phase> get() = backupManager.exportPhase
    val importPhase: StateFlow<BackupManager.Phase> get() = backupManager.importPhase

    private val _pendingRecoveryCode = MutableStateFlow<String?>(null)
    /**
     * 首次匯出時剛生成、尚未經使用者驗證 retype 的恢復碼(52 字元正規化形式).
     * Verify 通過後寫入 [RecoveryCodeStore] 並清掉這個 state.
     */
    val pendingRecoveryCode: StateFlow<String?> = _pendingRecoveryCode.asStateFlow()

    /** 已存在的恢復碼(若存在). Null 表示首次匯出,需先生成. */
    fun storedRecoveryCode(): String? = recoveryStore.get()

    /** 取得使用者面向的分組顯示形式. */
    fun grouped(code: String): String = RecoveryCode.formatGrouped(code)

    /** 首次匯出: 產生新恢復碼進 pending state. */
    fun generateRecoveryCode() {
        _pendingRecoveryCode.value = RecoveryCode.generate()
    }

    /** 驗證使用者回打的字串符合 pendingRecoveryCode 的指定字組. */
    fun verifyGroupMatches(groupIndex: Int, userInput: String): Boolean {
        val pending = _pendingRecoveryCode.value ?: return false
        val expected = RecoveryCode.groupAt(pending, groupIndex)
        return RecoveryCode.canonicalize(userInput).equals(expected, ignoreCase = true)
    }

    /** 使用者完成 retype 驗證 → 把 pending 寫入持久儲存. */
    fun commitRecoveryCode() {
        val code = _pendingRecoveryCode.value ?: return
        recoveryStore.set(code)
        _pendingRecoveryCode.value = null
    }

    /** 使用者選擇重新生成恢復碼(會讓既有備份檔變得只能用 Keystore 包裝在原裝置開啟). */
    fun rotateRecoveryCode() {
        recoveryStore.clear()
        _pendingRecoveryCode.value = RecoveryCode.generate()
    }

    /**
     * 匯出 — 由畫面在使用者選好 SAF destination 之後呼叫.
     * [passphrase] 可以是原始 52 字元也可以是含連字號的分組顯示形式;
     * [BackupManager] 內部會做 decode 正規化.
     */
    fun export(
        uri: Uri,
        passphrase: String,
        options: BackupManager.ExportOptions = BackupManager.ExportOptions(),
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { os ->
                    backupManager.export(os, passphrase, options)
                } ?: error("無法開啟匯出目的地")
            }
        }
    }

    /**
     * 匯入 — [passphrase] = null 時先試 Keystore 包裝(同裝置),
     * 失敗了畫面端會回頭要求使用者輸入恢復碼.
     */
    fun import(
        uri: Uri,
        passphrase: String?,
        mode: BackupManager.ImportMode = BackupManager.ImportMode.Merge,
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    backupManager.import(input, passphrase, mode)
                } ?: error("無法開啟匯入來源")
            }
        }
    }
}
