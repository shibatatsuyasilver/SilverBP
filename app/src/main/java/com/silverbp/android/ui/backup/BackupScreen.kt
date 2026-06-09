package com.silverbp.android.ui.backup

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.backup.BackupManager
import com.silverbp.android.backup.auto.AutoBackupFrequency
import com.silverbp.android.backup.auto.GoogleDriveBackupClient
import com.silverbp.android.di.ServiceLocator

/**
 * 單一進入點 — 匯出、匯入、檢視恢復碼、自動備份四個流程都在這頁,以子對話框/區塊切換.
 *
 * 流程:
 *  - **自動備份**: 連結 Google 帳號 → 選頻率 (關閉 / 每日 / 每週 / 每月).
 *    觸發任一動作前若沒設恢復碼, 先彈出 ShowCode → VerifyCode gate.
 *  - **手動匯出**: 若沒存過恢復碼 → 先生成 + 讓使用者抄寫並驗證一組 → 寫入 store →
 *    SAF CreateDocument → 呼 BackupManager.export.
 *  - **匯入**: 來自 SAF 檔案或 Drive 列表 → 走 BackupManager.import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    vm: BackupViewModel = viewModel(),
) {
    val context = LocalContext.current
    val exportPhase by vm.exportPhase.collectAsStateWithLifecycle()
    val importPhase by vm.importPhase.collectAsStateWithLifecycle()
    val pending by vm.pendingRecoveryCode.collectAsStateWithLifecycle()
    val userSettings by ServiceLocator.userSettings.flow.collectAsStateWithLifecycle(initialValue = null)
    val pendingConsent by vm.pendingConsentIntent.collectAsStateWithLifecycle()
    val autoBackupRunning by vm.autoBackupRunning.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()

    var dialog: Dialog by remember { mutableStateOf(Dialog.None) }
    var gateTarget: GateTarget? by remember { mutableStateOf(null) }
    var accountMenuOpen by remember { mutableStateOf(false) }

    val accountEmail = userSettings?.googleAccountEmail.orEmpty()
    val frequency = userSettings?.autoBackupFrequency ?: AutoBackupFrequency.Off
    val lastBackupAt = userSettings?.lastBackupAtMs ?: 0L
    val lastError = userSettings?.lastBackupError.orEmpty()
    val lastErrorAt = userSettings?.lastBackupErrorAtMs ?: 0L
    val hasAccount = accountEmail.isNotBlank()
    val hasRecoveryCode = vm.storedRecoveryCode() != null
    val autoControlsEnabled = hasAccount && hasRecoveryCode

    // Launcher for Google consent IntentSender. ViewModel parks the sender in
    // pendingConsentIntent; the LaunchedEffect below kicks it off, and we
    // hand the resulting Intent back to the VM here.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.completeGoogleConsent(result.data) }
    LaunchedEffect(pendingConsent) {
        pendingConsent?.let { sender ->
            consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }
    LaunchedEffect(Unit) {
        vm.autoBackupErrors.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun runWithRecoveryGate(target: GateTarget, action: () -> Unit) {
        if (hasRecoveryCode) {
            action()
        } else {
            gateTarget = target
            dialog = Dialog.RecoveryGate
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.backup_back))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.backup_intro_description),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            // ============================================================
            // 自動備份 (Google Drive)
            // ============================================================

            Text(stringResource(R.string.backup_auto_section_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.backup_auto_section_help),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_auto_account_label), style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (hasAccount) accountEmail
                        else stringResource(R.string.backup_auto_account_unlinked),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (hasAccount) {
                    OutlinedButton(onClick = { accountMenuOpen = true }) {
                        Text(stringResource(R.string.backup_auto_switch_account))
                    }
                    DropdownMenu(
                        expanded = accountMenuOpen,
                        onDismissRequest = { accountMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.backup_auto_switch_account)) },
                            onClick = {
                                accountMenuOpen = false
                                runWithRecoveryGate(GateTarget.ConnectGoogle) { vm.startGoogleConnect() }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.backup_auto_disconnect)) },
                            onClick = {
                                accountMenuOpen = false
                                dialog = Dialog.DisconnectConfirm
                            },
                        )
                    }
                } else {
                    OutlinedButton(onClick = {
                        runWithRecoveryGate(GateTarget.ConnectGoogle) { vm.startGoogleConnect() }
                    }) {
                        Text(stringResource(R.string.backup_auto_connect_button))
                    }
                }
            }

            // Frequency selector
            Text(stringResource(R.string.backup_auto_frequency_label), style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    AutoBackupFrequency.Off to R.string.backup_auto_freq_off,
                    AutoBackupFrequency.Daily to R.string.backup_auto_freq_daily,
                    AutoBackupFrequency.Weekly to R.string.backup_auto_freq_weekly,
                    AutoBackupFrequency.Monthly to R.string.backup_auto_freq_monthly,
                )
                options.forEachIndexed { index, (freqOption, labelRes) ->
                    // 「關閉」永遠可選 — 即使沒帳號或沒恢復碼也應該能把已啟用的排程關掉.
                    val itemEnabled = autoControlsEnabled || freqOption == AutoBackupFrequency.Off
                    SegmentedButton(
                        selected = frequency == freqOption,
                        onClick = {
                            if (freqOption == AutoBackupFrequency.Off) {
                                vm.setFrequency(AutoBackupFrequency.Off)
                            } else {
                                runWithRecoveryGate(GateTarget.SetFrequency(freqOption)) {
                                    vm.setFrequency(freqOption)
                                }
                            }
                        },
                        enabled = itemEnabled,
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(stringResource(labelRes)) }
                }
            }

            OutlinedButton(
                onClick = {
                    runWithRecoveryGate(GateTarget.BackupNow) { vm.backupNow() }
                },
                enabled = autoControlsEnabled && !autoBackupRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (autoBackupRunning) stringResource(R.string.backup_auto_running)
                    else stringResource(R.string.backup_auto_run_now)
                )
            }

            // Status row
            val statusText = when {
                autoBackupRunning -> stringResource(R.string.backup_auto_running)
                lastError.isNotBlank() ->
                    stringResource(R.string.backup_auto_status_failure, lastError)
                lastBackupAt > 0L ->
                    stringResource(R.string.backup_auto_status_success, formatTimestamp(lastBackupAt))
                else -> stringResource(R.string.backup_auto_status_never)
            }
            val statusColor = when {
                lastError.isNotBlank() && lastErrorAt >= lastBackupAt ->
                    MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)

            if (!autoControlsEnabled) {
                Text(
                    stringResource(R.string.backup_auto_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.backup_auto_drive_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // ============================================================
            // 匯出加密備份 (manual SAF flow — unchanged)
            // ============================================================

            Text(stringResource(R.string.backup_export_encrypted_title), style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { dialog = Dialog.Export },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_export_button)) }
            Text(
                stringResource(R.string.backup_export_first_time_note),
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            // ============================================================
            // 從備份還原 (SAF + new Drive restore)
            // ============================================================

            Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { dialog = Dialog.Import },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_import_button)) }
            if (hasAccount) {
                OutlinedButton(
                    onClick = {
                        vm.refreshDriveListings()
                        dialog = Dialog.DriveRestore
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_drive_restore_button)) }
            }
            Text(
                stringResource(R.string.backup_restore_description),
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            // ============================================================
            // 恢復碼管理 (existing)
            // ============================================================

            Text(stringResource(R.string.backup_recovery_management_title), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { dialog = Dialog.ViewRecovery },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.storedRecoveryCode() != null,
            ) { Text(stringResource(R.string.backup_recovery_view_again)) }
            OutlinedButton(
                onClick = { dialog = Dialog.RotateRecovery },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_recovery_regenerate)) }
            Text(
                stringResource(R.string.backup_recovery_regenerate_warning),
                style = MaterialTheme.typography.bodySmall,
            )

            // 進度狀態
            val activePhase: BackupManager.Phase? = when {
                exportPhase !is BackupManager.Phase.Idle -> exportPhase
                importPhase !is BackupManager.Phase.Idle -> importPhase
                else -> null
            }
            activePhase?.let { PhaseRow(it) }

            // ============================================================
            // Danger zone — delete account & data (Play requirement for
            // sign-in apps; also satisfies the in-app deletion mandate).
            // ============================================================
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(
                onClick = { dialog = Dialog.DeleteAccount },
                enabled = !deleting,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(
                    if (deleting) stringResource(R.string.backup_delete_account_running)
                    else stringResource(R.string.backup_delete_account_button)
                )
            }
            Text(
                stringResource(R.string.backup_delete_account_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (dialog) {
            Dialog.None -> Unit
            Dialog.Export -> ExportDialog(
                pendingCode = pending,
                vm = vm,
                onDismiss = { dialog = Dialog.None },
            )
            Dialog.Import -> ImportDialog(
                vm = vm,
                onDismiss = { dialog = Dialog.None },
            )
            Dialog.ViewRecovery -> ViewRecoveryDialog(
                code = vm.storedRecoveryCode().orEmpty(),
                grouped = vm.storedRecoveryCode()?.let(vm::grouped).orEmpty(),
                onCopy = {
                    vm.storedRecoveryCode()?.let {
                        vm.copyRecoveryCodeToClipboard(it)
                        Toast.makeText(context, R.string.backup_recovery_copied, Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { dialog = Dialog.None },
            )
            Dialog.RotateRecovery -> AlertDialog(
                onDismissRequest = { dialog = Dialog.None },
                title = { Text(stringResource(R.string.backup_rotate_dialog_title)) },
                text = { Text(stringResource(R.string.backup_rotate_warning)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.rotateRecoveryCode()
                        dialog = Dialog.Export
                    }) { Text(stringResource(R.string.backup_rotate_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = Dialog.None }) { Text(stringResource(R.string.cancel)) }
                },
            )
            Dialog.RecoveryGate -> RecoveryGateDialog(
                pendingCode = pending,
                vm = vm,
                onDismiss = {
                    dialog = Dialog.None
                    gateTarget = null
                },
                onCommitted = {
                    val target = gateTarget
                    gateTarget = null
                    dialog = Dialog.None
                    when (target) {
                        GateTarget.ConnectGoogle -> vm.startGoogleConnect()
                        is GateTarget.SetFrequency -> vm.setFrequency(target.freq)
                        GateTarget.BackupNow -> vm.backupNow()
                        null -> Unit
                    }
                },
                onCopy = {
                    pending?.let {
                        vm.copyRecoveryCodeToClipboard(it)
                        Toast.makeText(context, R.string.backup_recovery_copied, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Dialog.DisconnectConfirm -> AlertDialog(
                onDismissRequest = { dialog = Dialog.None },
                title = { Text(stringResource(R.string.backup_auto_disconnect_title)) },
                text = { Text(stringResource(R.string.backup_auto_disconnect_msg)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.disconnectGoogle()
                        dialog = Dialog.None
                    }) { Text(stringResource(R.string.backup_auto_disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = Dialog.None }) { Text(stringResource(R.string.cancel)) }
                },
            )
            Dialog.DriveRestore -> DriveRestoreDialog(
                vm = vm,
                onDismiss = {
                    vm.resetDriveListings()
                    dialog = Dialog.None
                },
            )
            Dialog.DeleteAccount -> AlertDialog(
                onDismissRequest = { dialog = Dialog.None },
                title = { Text(stringResource(R.string.backup_delete_account_title)) },
                text = { Text(stringResource(R.string.backup_delete_account_msg)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteAccountAndData()
                        dialog = Dialog.None
                    }) {
                        Text(
                            stringResource(R.string.backup_delete_account_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = Dialog.None }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

private enum class Dialog {
    None,
    Export,
    Import,
    ViewRecovery,
    RotateRecovery,
    RecoveryGate,
    DisconnectConfirm,
    DriveRestore,
    DeleteAccount,
}

private sealed class GateTarget {
    object ConnectGoogle : GateTarget()
    data class SetFrequency(val freq: AutoBackupFrequency) : GateTarget()
    object BackupNow : GateTarget()
}

@Composable
private fun PhaseRow(phase: BackupManager.Phase) {
    Spacer(Modifier.size(8.dp))
    HorizontalDivider()
    when (phase) {
        is BackupManager.Phase.Collecting -> {
            Text(stringResource(R.string.backup_phase_collecting, phase.recordCount))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is BackupManager.Phase.Encoding -> {
            Text(stringResource(R.string.backup_phase_encoding))
            LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
        }
        BackupManager.Phase.Encrypting -> {
            Text(stringResource(R.string.backup_phase_encrypting))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        BackupManager.Phase.Writing -> {
            Text(stringResource(R.string.backup_phase_writing))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is BackupManager.Phase.Success -> {
            Column {
                Text(stringResource(R.string.backup_phase_success, phase.recordCount, formatSize(phase.byteCount)))
                if (phase.skippedCount > 0) {
                    Text(
                        stringResource(R.string.backup_phase_skipped, phase.skippedCount),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        is BackupManager.Phase.Failure -> {
            Text(
                stringResource(R.string.backup_phase_failure, phase.error.localizedMessage ?: (phase.error::class.simpleName ?: "")),
                color = MaterialTheme.colorScheme.error,
            )
        }
        BackupManager.Phase.Idle -> Unit
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatTimestamp(ms: Long): String {
    if (ms <= 0L) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%04d/%02d/%02d %02d:%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

// ============================================================
// Export dialog (含首次設定恢復碼流程)
// ============================================================

@Composable
private fun ExportDialog(
    pendingCode: String?,
    vm: BackupViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var step: ExportStep by remember {
        mutableStateOf(
            if (vm.storedRecoveryCode() != null) ExportStep.Options
            else ExportStep.ShowCode
        )
    }
    var includeChat by remember { mutableStateOf(true) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            val passphrase = vm.storedRecoveryCode() ?: return@rememberLauncherForActivityResult
            vm.export(
                uri = uri,
                passphrase = passphrase,
                options = BackupManager.ExportOptions(includeChat = includeChat),
            )
            onDismiss()
        }
    }

    when (step) {
        ExportStep.ShowCode -> {
            if (pendingCode == null) {
                LaunchedEffectOnce { vm.generateRecoveryCode() }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.backup_recovery_code_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.backup_code_write_down))
                        RecoveryCodeBlock(
                            grouped = pendingCode?.let(vm::grouped) ?: "…",
                            enabled = pendingCode != null,
                            onCopy = {
                                pendingCode?.let {
                                    vm.copyRecoveryCodeToClipboard(it)
                                    Toast.makeText(context, R.string.backup_recovery_copied, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        Text(
                            stringResource(R.string.backup_code_charset_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { step = ExportStep.VerifyCode },
                        enabled = pendingCode != null,
                    ) { Text(stringResource(R.string.backup_code_copied_next)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                },
            )
        }

        ExportStep.VerifyCode -> {
            val verifyGroupIndex = remember { listOf(2, 6, 11).random() }
            var input by remember { mutableStateOf("") }
            var error by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.backup_verify_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.backup_verify_group_prompt, verifyGroupIndex + 1))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.uppercase(); error = false },
                            singleLine = true,
                            isError = error,
                        )
                        if (error) {
                            Text(stringResource(R.string.backup_code_incorrect), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (vm.verifyGroupMatches(verifyGroupIndex, input)) {
                            vm.commitRecoveryCode()
                            step = ExportStep.Options
                        } else {
                            error = true
                        }
                    }) { Text(stringResource(R.string.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                },
            )
        }

        ExportStep.Options -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.backup_export_options_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.backup_export_options_instruction))
                        ToggleLine(
                            label = stringResource(R.string.backup_export_include_chat),
                            checked = includeChat,
                            onChange = { includeChat = it },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val fileName = "SilverBP-Backup-${nowFileTimestamp()}.sbpbk"
                        launcher.launch(fileName)
                    }) { Text(stringResource(R.string.backup_choose_location)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

private enum class ExportStep { ShowCode, VerifyCode, Options }

// ============================================================
// Recovery code gate (for auto-backup actions)
// ============================================================

@Composable
private fun RecoveryGateDialog(
    pendingCode: String?,
    vm: BackupViewModel,
    onDismiss: () -> Unit,
    onCommitted: () -> Unit,
    onCopy: () -> Unit,
) {
    var step: GateStep by remember { mutableStateOf(GateStep.ShowCode) }

    when (step) {
        GateStep.ShowCode -> {
            if (pendingCode == null) {
                LaunchedEffectOnce { vm.generateRecoveryCode() }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.backup_auto_recovery_required_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.backup_auto_recovery_required_msg))
                        RecoveryCodeBlock(
                            grouped = pendingCode?.let(vm::grouped) ?: "…",
                            enabled = pendingCode != null,
                            onCopy = onCopy,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { step = GateStep.VerifyCode },
                        enabled = pendingCode != null,
                    ) { Text(stringResource(R.string.backup_code_copied_next)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        GateStep.VerifyCode -> {
            val verifyGroupIndex = remember { listOf(2, 6, 11).random() }
            var input by remember { mutableStateOf("") }
            var error by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.backup_verify_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.backup_verify_group_prompt, verifyGroupIndex + 1))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.uppercase(); error = false },
                            singleLine = true,
                            isError = error,
                        )
                        if (error) {
                            Text(stringResource(R.string.backup_code_incorrect), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (vm.verifyGroupMatches(verifyGroupIndex, input)) {
                            vm.commitRecoveryCode()
                            onCommitted()
                        } else {
                            error = true
                        }
                    }) { Text(stringResource(R.string.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

private enum class GateStep { ShowCode, VerifyCode }

@Composable
private fun RecoveryCodeBlock(
    grouped: String,
    enabled: Boolean,
    onCopy: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            grouped,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy, enabled = enabled) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.backup_recovery_copy),
            )
        }
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun LaunchedEffectOnce(block: () -> Unit) {
    LaunchedEffect(Unit) { block() }
}

private fun nowFileTimestamp(): String {
    val cal = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d-%02d%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

// ============================================================
// Import dialog (SAF) — unchanged behaviour
// ============================================================

@Composable
private fun ImportDialog(
    vm: BackupViewModel,
    onDismiss: () -> Unit,
) {
    var step: ImportStep by remember { mutableStateOf(ImportStep.PickFile) }
    var pickedUri: android.net.Uri? by remember { mutableStateOf(null) }
    var passphrase by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(BackupManager.ImportMode.Merge) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            step = ImportStep.Confirm
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(step) {
        if (step == ImportStep.PickFile) launcher.launch(arrayOf("*/*"))
    }

    if (step == ImportStep.Confirm) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.backup_import_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_recovery_instruction))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it.uppercase() },
                        singleLine = true,
                        label = { Text(stringResource(R.string.backup_recovery_placeholder)) },
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.backup_import_mode_title))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Merge,
                            onClick = { mode = BackupManager.ImportMode.Merge },
                        )
                        Text(stringResource(R.string.backup_import_mode_merge))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Replace,
                            onClick = { mode = BackupManager.ImportMode.Replace },
                        )
                        Text(stringResource(R.string.backup_import_mode_replace))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pickedUri ?: return@TextButton
                    vm.import(
                        uri = uri,
                        passphrase = passphrase.trim().ifBlank { null },
                        mode = mode,
                    )
                    onDismiss()
                }) { Text(stringResource(R.string.backup_start_import)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private enum class ImportStep { PickFile, Confirm }

// ============================================================
// Drive restore dialog (new)
// ============================================================

@Composable
private fun DriveRestoreDialog(
    vm: BackupViewModel,
    onDismiss: () -> Unit,
) {
    val state by vm.driveListings.collectAsStateWithLifecycle()
    var selected: GoogleDriveBackupClient.DriveBackupFile? by remember { mutableStateOf(null) }
    var passphrase by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(BackupManager.ImportMode.Merge) }

    val sel = selected
    if (sel == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.backup_drive_restore_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (val s = state) {
                        BackupViewModel.DriveListingState.Idle,
                        BackupViewModel.DriveListingState.Loading ->
                            Text(stringResource(R.string.backup_drive_restore_loading))
                        is BackupViewModel.DriveListingState.Failed ->
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                        is BackupViewModel.DriveListingState.Loaded -> {
                            if (s.files.isEmpty()) {
                                Text(stringResource(R.string.backup_drive_restore_empty))
                            } else {
                                Text(stringResource(R.string.backup_drive_restore_select))
                                s.files.forEach { f ->
                                    OutlinedButton(
                                        onClick = { selected = f },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                stringResource(
                                                    R.string.backup_drive_restore_size,
                                                    formatRfc3339(f.createdTime),
                                                    formatSize(f.sizeBytes),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_close)) }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.backup_drive_restore_file_title, sel.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_recovery_instruction))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it.uppercase() },
                        singleLine = true,
                        label = { Text(stringResource(R.string.backup_recovery_placeholder)) },
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.backup_import_mode_title))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Merge,
                            onClick = { mode = BackupManager.ImportMode.Merge },
                        )
                        Text(stringResource(R.string.backup_import_mode_merge))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Replace,
                            onClick = { mode = BackupManager.ImportMode.Replace },
                        )
                        Text(stringResource(R.string.backup_import_mode_replace))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importFromDrive(
                        fileId = sel.id,
                        passphrase = passphrase.trim().ifBlank { null },
                        mode = mode,
                    )
                    onDismiss()
                }) { Text(stringResource(R.string.backup_start_import)) }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.backup_back_to_list)) }
            },
        )
    }
}

private fun formatRfc3339(iso: String): String {
    if (iso.isBlank()) return ""
    // Drive returns e.g. "2026-05-27T08:00:00.000Z" — show as YYYY/MM/DD HH:mm in local time.
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        val local = instant.atZone(java.time.ZoneId.systemDefault())
        "%04d/%02d/%02d %02d:%02d".format(
            local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute,
        )
    }.getOrDefault(iso)
}

// ============================================================
// View recovery dialog (existing, plus copy button)
// ============================================================

@Composable
private fun ViewRecoveryDialog(
    code: String,
    grouped: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_recovery_code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (code.isBlank()) {
                    Text(stringResource(R.string.backup_recovery_none_yet))
                } else {
                    RecoveryCodeBlock(grouped = grouped, enabled = true, onCopy = onCopy)
                    Text(
                        stringResource(R.string.backup_recovery_save_warning),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_close)) }
        },
    )
}
