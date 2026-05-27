package com.silverbp.android.ui.backup

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.silverbp.android.backup.BackupManager
import com.silverbp.android.backup.RecoveryCode
import com.silverbp.android.backup.RecoveryCodeStore
import com.silverbp.android.backup.auto.AutoBackupFrequency
import com.silverbp.android.backup.auto.AutoBackupWorker
import com.silverbp.android.backup.auto.GoogleAuthClient
import com.silverbp.android.backup.auto.GoogleDriveBackupClient
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Backs the expanded BackupScreen — handles both the existing manual
 * SAF export/import flows and the new Google Drive auto-backup feature.
 *
 * Auto-backup flow:
 *  - UI calls [startGoogleConnect]; ViewModel either finishes silently or
 *    surfaces an [IntentSender] in [pendingConsentIntent] for the UI to
 *    launch through `rememberLauncherForActivityResult(StartIntentSenderForResult)`.
 *  - Once the launcher returns, UI calls [completeGoogleConsent] with the
 *    Intent and the ViewModel finalises by hitting Drive's `/about` to
 *    record the linked email + permission id.
 *  - [setFrequency] persists the cadence and reschedules WorkManager.
 *  - [backupNow] kicks an immediate one-shot upload through the same worker.
 *  - [refreshDriveListings] / [importFromDrive] back the new Drive restore
 *    sub-flow that's required because `drive.appdata` is invisible in the
 *    user's regular Drive UI.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx get() = getApplication<Application>()
    private val backupManager: BackupManager = ServiceLocator.backupManager
    private val recoveryStore: RecoveryCodeStore by lazy { RecoveryCodeStore.create(ctx) }

    private val auth = ServiceLocator.googleAuthClient
    private val drive = ServiceLocator.googleDriveBackupClient
    private val scheduler = ServiceLocator.autoBackupScheduler
    private val settings = ServiceLocator.userSettings

    val exportPhase: StateFlow<BackupManager.Phase> get() = backupManager.exportPhase
    val importPhase: StateFlow<BackupManager.Phase> get() = backupManager.importPhase

    private val _pendingRecoveryCode = MutableStateFlow<String?>(null)
    /**
     * 首次匯出時剛生成、尚未經使用者驗證 retype 的恢復碼(52 字元正規化形式).
     * Verify 通過後寫入 [RecoveryCodeStore] 並清掉這個 state.
     */
    val pendingRecoveryCode: StateFlow<String?> = _pendingRecoveryCode.asStateFlow()

    // ============================================================
    // Recovery code helpers (reused by both manual export and the
    // auto-backup recovery-code gate)
    // ============================================================

    /** 已存在的恢復碼(若存在). Null 表示首次匯出,需先生成. */
    fun storedRecoveryCode(): String? = recoveryStore.get()

    fun grouped(code: String): String = RecoveryCode.formatGrouped(code)

    fun generateRecoveryCode() {
        _pendingRecoveryCode.value = RecoveryCode.generate()
    }

    fun verifyGroupMatches(groupIndex: Int, userInput: String): Boolean {
        val pending = _pendingRecoveryCode.value ?: return false
        val expected = RecoveryCode.groupAt(pending, groupIndex)
        return RecoveryCode.canonicalize(userInput).equals(expected, ignoreCase = true)
    }

    fun commitRecoveryCode() {
        val code = _pendingRecoveryCode.value ?: return
        recoveryStore.set(code)
        _pendingRecoveryCode.value = null
    }

    fun rotateRecoveryCode() {
        recoveryStore.clear()
        _pendingRecoveryCode.value = RecoveryCode.generate()
    }

    /**
     * Copy a recovery code to the system clipboard with the
     * `EXTRA_IS_SENSITIVE` flag so Android 13+ doesn't render the value in
     * the floating clipboard preview that pops over the keyboard.
     */
    fun copyRecoveryCodeToClipboard(code: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SilverBP recovery code", grouped(code))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
    }

    // ============================================================
    // Manual export / import (existing flows — unchanged behaviour)
    // ============================================================

    fun export(
        uri: Uri,
        passphrase: String,
        options: BackupManager.ExportOptions = BackupManager.ExportOptions(),
    ) {
        viewModelScope.launch {
            // Swallow exceptions — BackupManager has already published the
            // failure to exportPhase, so the UI's PhaseRow will render the
            // error. Letting the exception escape would crash the process.
            runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        backupManager.export(os, passphrase, options)
                    } ?: error("無法開啟匯出目的地")
                }
            }
        }
    }

    fun import(
        uri: Uri,
        passphrase: String?,
        mode: BackupManager.ImportMode = BackupManager.ImportMode.Merge,
    ) {
        viewModelScope.launch {
            // See note on export() — BackupManager already surfaces failure
            // via importPhase + PhaseRow; the rethrow from BackupManager.import
            // would otherwise crash the process when keystore unwrap fails
            // and the user left the passphrase blank.
            runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        backupManager.import(input, passphrase, mode)
                    } ?: error("無法開啟匯入來源")
                }
            }
        }
    }

    // ============================================================
    // Auto-backup state
    // ============================================================

    /**
     * Whether an AutoBackupWorker is currently running.
     *
     * Discrimination matters because **periodic workers sit in ENQUEUED between
     * runs** — that's their idle state, not "in progress". One-shot workers,
     * by contrast, are ENQUEUED only briefly before transitioning to RUNNING,
     * so we treat their ENQUEUED as "about to run".
     */
    val autoBackupRunning: StateFlow<Boolean> = run {
        val wm = WorkManager.getInstance(ctx)
        val periodic = wm.getWorkInfosForUniqueWorkFlow(AutoBackupWorker.UNIQUE_NAME)
        val once = wm.getWorkInfosForUniqueWorkFlow("${AutoBackupWorker.UNIQUE_NAME}.once")
        combine(periodic, once) { a, b ->
            val periodicRunning = a.any { it.state == WorkInfo.State.RUNNING }
            val onceActive = b.any {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }
            periodicRunning || onceActive
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    }

    private val _pendingConsentIntent = MutableStateFlow<IntentSender?>(null)
    val pendingConsentIntent: StateFlow<IntentSender?> = _pendingConsentIntent.asStateFlow()

    private val _autoBackupErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val autoBackupErrors: SharedFlow<String> = _autoBackupErrors.asSharedFlow()

    /**
     * Start linking a Google account. Either resolves silently (rare —
     * implies the system already had this scope granted to this app) or
     * publishes an [IntentSender] on [pendingConsentIntent] for the UI to
     * launch via ActivityResultLauncher.
     */
    fun startGoogleConnect() {
        viewModelScope.launch {
            try {
                when (val r = auth.requestDriveToken(null)) {
                    is GoogleAuthClient.TokenResult.Granted -> finishConnect(r.accessToken)
                    is GoogleAuthClient.TokenResult.NeedsConsent ->
                        _pendingConsentIntent.value = r.intentSender
                    GoogleAuthClient.TokenResult.Cancelled -> emitError("已取消連結")
                }
            } catch (t: Throwable) {
                emitError(t.localizedMessage ?: "Google 連結失敗")
            }
        }
    }

    /** Called by the UI from the ActivityResultLauncher callback. */
    fun completeGoogleConsent(data: Intent?) {
        _pendingConsentIntent.value = null
        viewModelScope.launch {
            try {
                when (val r = auth.parseConsentResult(data)) {
                    is GoogleAuthClient.TokenResult.Granted -> finishConnect(r.accessToken)
                    is GoogleAuthClient.TokenResult.NeedsConsent,
                    GoogleAuthClient.TokenResult.Cancelled -> emitError("授權未完成")
                }
            } catch (t: Throwable) {
                emitError(t.localizedMessage ?: "授權失敗")
            }
        }
    }

    private suspend fun finishConnect(token: String) {
        val user = withContext(Dispatchers.IO) { drive.whoAmI(token) }
        settings.setGoogleAccount(user.email, user.permissionId)
    }

    fun disconnectGoogle() {
        viewModelScope.launch {
            scheduler.cancel()
            settings.setAutoBackupFrequency(AutoBackupFrequency.Off)
            settings.clearGoogleAccount()
        }
    }

    fun setFrequency(freq: AutoBackupFrequency) {
        viewModelScope.launch {
            settings.setAutoBackupFrequency(freq)
            scheduler.enqueue(freq) // Off cancels internally
        }
    }

    fun backupNow() {
        scheduler.runNow()
    }

    // ============================================================
    // Drive restore (required since drive.appdata is invisible in the
    // user's regular Drive UI — they can only see backups through us).
    // ============================================================

    sealed class DriveListingState {
        object Idle : DriveListingState()
        object Loading : DriveListingState()
        data class Loaded(val files: List<GoogleDriveBackupClient.DriveBackupFile>) : DriveListingState()
        data class Failed(val message: String) : DriveListingState()
    }

    private val _driveListings = MutableStateFlow<DriveListingState>(DriveListingState.Idle)
    val driveListings: StateFlow<DriveListingState> = _driveListings.asStateFlow()

    fun refreshDriveListings() {
        viewModelScope.launch {
            _driveListings.value = DriveListingState.Loading
            try {
                val email = settings.flow.first().googleAccountEmail
                if (email.isBlank()) {
                    _driveListings.value = DriveListingState.Failed("尚未連結 Google 帳號")
                    return@launch
                }
                val token = when (val r = auth.requestDriveToken(email)) {
                    is GoogleAuthClient.TokenResult.Granted -> r.accessToken
                    else -> {
                        _driveListings.value = DriveListingState.Failed("需重新連結 Google 帳號")
                        return@launch
                    }
                }
                val list = withContext(Dispatchers.IO) { drive.listBackups(token) }
                _driveListings.value = DriveListingState.Loaded(list)
            } catch (t: Throwable) {
                _driveListings.value = DriveListingState.Failed(t.localizedMessage ?: "讀取失敗")
            }
        }
    }

    fun resetDriveListings() {
        _driveListings.value = DriveListingState.Idle
    }

    fun importFromDrive(
        fileId: String,
        passphrase: String?,
        mode: BackupManager.ImportMode = BackupManager.ImportMode.Merge,
    ) {
        viewModelScope.launch {
            val email = settings.flow.first().googleAccountEmail
            if (email.isBlank()) {
                emitError("尚未連結 Google 帳號")
                return@launch
            }
            val token = when (val r = auth.requestDriveToken(email)) {
                is GoogleAuthClient.TokenResult.Granted -> r.accessToken
                else -> {
                    emitError("需重新連結 Google 帳號")
                    return@launch
                }
            }
            // See note on import() — BackupManager.import rethrows, would
            // otherwise crash the process. PhaseRow surfaces the failure.
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = drive.downloadFile(fileId, token)
                    backupManager.import(ByteArrayInputStream(bytes), passphrase, mode)
                }
            }
        }
    }

    private fun emitError(msg: String) {
        viewModelScope.launch { _autoBackupErrors.emit(msg) }
    }
}
