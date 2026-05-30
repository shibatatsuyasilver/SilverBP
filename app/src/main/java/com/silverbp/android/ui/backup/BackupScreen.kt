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
                title = { Text("資料備份") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                "把所有血壓、運動、用藥與 Coach 記錄打包成加密快照, 存到你選擇的雲端或本機檔案. 換手機或重灌時可用恢復碼還原.",
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

            Text("匯出加密備份", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { dialog = Dialog.Export },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("匯出備份 (.sbpbk)") }
            Text(
                "首次匯出會產生 52 字元恢復碼 — 必須抄寫保存. 換裝置/重灌後沒有它就無法解開備份.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            // ============================================================
            // 從備份還原 (SAF + new Drive restore)
            // ============================================================

            Text("從備份還原", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { dialog = Dialog.Import },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("匯入備份檔") }
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
                "可在新裝置或重灌後讀回 .sbpbk 檔. 預設與本機資料合併, 進階模式可改為清空後匯入.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            // ============================================================
            // 恢復碼管理 (existing)
            // ============================================================

            Text("恢復碼管理", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { dialog = Dialog.ViewRecovery },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.storedRecoveryCode() != null,
            ) { Text("再看一次恢復碼") }
            OutlinedButton(
                onClick = { dialog = Dialog.RotateRecovery },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重新產生恢復碼") }
            Text(
                "重新產生會讓既有的備份檔在新裝置上無法開啟(只能在原裝置以 Keystore 路徑解開).",
                style = MaterialTheme.typography.bodySmall,
            )

            // 進度狀態
            val activePhase: BackupManager.Phase? = when {
                exportPhase !is BackupManager.Phase.Idle -> exportPhase
                importPhase !is BackupManager.Phase.Idle -> importPhase
                else -> null
            }
            activePhase?.let { PhaseRow(it) }
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
                title = { Text("重新產生恢復碼?") },
                text = { Text("舊的恢復碼會失效. 既有的 .sbpbk 備份檔在新裝置/重灌後將無法用恢復碼開啟(只能用原本生成它的裝置的 Keystore). 確定繼續?") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.rotateRecoveryCode()
                        dialog = Dialog.Export
                    }) { Text("確定") }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = Dialog.None }) { Text("取消") }
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
                    TextButton(onClick = { dialog = Dialog.None }) { Text("取消") }
                },
            )
            Dialog.DriveRestore -> DriveRestoreDialog(
                vm = vm,
                onDismiss = {
                    vm.resetDriveListings()
                    dialog = Dialog.None
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
            Text("收集資料中… (${phase.recordCount} 筆)")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is BackupManager.Phase.Encoding -> {
            Text("編碼中…")
            LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
        }
        BackupManager.Phase.Encrypting -> {
            Text("加密中…")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        BackupManager.Phase.Writing -> {
            Text("寫入檔案中…")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is BackupManager.Phase.Success -> {
            Column {
                Text("完成 — 共 ${phase.recordCount} 筆紀錄, ${formatSize(phase.byteCount)}.")
                if (phase.skippedCount > 0) {
                    Text(
                        "⚠ 有 ${phase.skippedCount} 筆無法匯入(可能格式不符或關聯缺失),已略過.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        is BackupManager.Phase.Failure -> {
            Text(
                "失敗: ${phase.error.localizedMessage ?: phase.error::class.simpleName}",
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
                title = { Text("你的恢復碼") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("請抄寫下面 52 個字元(分成 13 組). 換裝置或重灌後須輸入這串字元才能還原備份.")
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
                            "提醒: I→1, L→1, O→0, U→V — 看不清楚的字元都會自動容錯, 但建議直接抄正確.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { step = ExportStep.VerifyCode },
                        enabled = pendingCode != null,
                    ) { Text("已抄寫, 下一步") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            )
        }

        ExportStep.VerifyCode -> {
            val verifyGroupIndex = remember { listOf(2, 6, 11).random() }
            var input by remember { mutableStateOf("") }
            var error by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("驗證你抄寫的恢復碼") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("請輸入第 ${verifyGroupIndex + 1} 組(共 13 組, 每組 4 字元):")
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.uppercase(); error = false },
                            singleLine = true,
                            isError = error,
                        )
                        if (error) {
                            Text("不正確, 請再試一次.", color = MaterialTheme.colorScheme.error)
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
                    }) { Text("確認") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            )
        }

        ExportStep.Options -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("匯出選項") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("選擇要包含的內容. 完成後會跳出系統檔案選擇器讓你決定存放位置(Google Drive / 本機 / 分享…)")
                        ToggleLine(
                            label = "包含聊天歷史",
                            checked = includeChat,
                            onChange = { includeChat = it },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val fileName = "SilverBP-Backup-${nowFileTimestamp()}.sbpbk"
                        launcher.launch(fileName)
                    }) { Text("選擇匯出位置") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
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
                    ) { Text("已抄寫, 下一步") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            )
        }
        GateStep.VerifyCode -> {
            val verifyGroupIndex = remember { listOf(2, 6, 11).random() }
            var input by remember { mutableStateOf("") }
            var error by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("驗證你抄寫的恢復碼") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("請輸入第 ${verifyGroupIndex + 1} 組(共 13 組, 每組 4 字元):")
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.uppercase(); error = false },
                            singleLine = true,
                            isError = error,
                        )
                        if (error) {
                            Text("不正確, 請再試一次.", color = MaterialTheme.colorScheme.error)
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
                    }) { Text("確認") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
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
            title = { Text("匯入備份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("輸入恢復碼(同裝置可留空, 系統會先試 Keystore 解開):")
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it.uppercase() },
                        singleLine = true,
                        label = { Text("52 字元 — 重灌 / 換手機請輸入；同裝置未重灌可留空") },
                    )
                    HorizontalDivider()
                    Text("合併模式")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Merge,
                            onClick = { mode = BackupManager.ImportMode.Merge },
                        )
                        Text("合併到本機(預設, 各筆紀錄走 LWW)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Replace,
                            onClick = { mode = BackupManager.ImportMode.Replace },
                        )
                        Text("清空本機後匯入(進階)")
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
                }) { Text("開始匯入") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
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
                TextButton(onClick = onDismiss) { Text("關閉") }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("從 Drive 還原 ${sel.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("輸入恢復碼(同裝置可留空, 系統會先試 Keystore 解開):")
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it.uppercase() },
                        singleLine = true,
                        label = { Text("52 字元 — 重灌 / 換手機請輸入；同裝置未重灌可留空") },
                    )
                    HorizontalDivider()
                    Text("合併模式")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Merge,
                            onClick = { mode = BackupManager.ImportMode.Merge },
                        )
                        Text("合併到本機(預設, 各筆紀錄走 LWW)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Replace,
                            onClick = { mode = BackupManager.ImportMode.Replace },
                        )
                        Text("清空本機後匯入(進階)")
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
                }) { Text("開始匯入") }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("返回清單") }
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
        title = { Text("你的恢復碼") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (code.isBlank()) {
                    Text("尚未產生恢復碼. 先匯出一次備份就會生成.")
                } else {
                    RecoveryCodeBlock(grouped = grouped, enabled = true, onCopy = onCopy)
                    Text(
                        "請保存在安全的地方 — 重灌或換裝置時必須輸入這串字元才能還原備份.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        },
    )
}
