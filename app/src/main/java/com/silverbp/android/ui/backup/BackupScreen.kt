package com.silverbp.android.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.backup.BackupManager

/**
 * 單一進入點 — 匯出、匯入、檢視恢復碼三個流程都在這頁,以子對話框/區塊切換.
 *
 * 流程:
 *  - **匯出**: 若沒存過恢復碼 → 先生成 + 讓使用者抄寫並驗證一組 → 寫入 store →
 *    SAF CreateDocument → 呼 BackupManager.export.
 *  - **匯入**: SAF OpenDocument → 視 Keystore unwrap 是否成功決定要不要要使用者
 *    輸入恢復碼 → 選 Merge/Replace → 呼 BackupManager.import.
 *  - **檢視恢復碼**: 直接顯示(若未啟用 app-lock 沒額外 gate;啟用時走 LockManager).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    vm: BackupViewModel = viewModel(),
) {
    val exportPhase by vm.exportPhase.collectAsStateWithLifecycle()
    val importPhase by vm.importPhase.collectAsStateWithLifecycle()
    val pending by vm.pendingRecoveryCode.collectAsStateWithLifecycle()

    // 主畫面顯示哪個對話框
    var dialog: Dialog by remember { mutableStateOf(Dialog.None) }

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

            Text("從備份還原", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { dialog = Dialog.Import },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("匯入備份檔") }
            Text(
                "可在新裝置或重灌後讀回 .sbpbk 檔. 預設與本機資料合併, 進階模式可改為清空後匯入.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

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
        }
    }
}

private enum class Dialog { None, Export, Import, ViewRecovery, RotateRecovery }

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
            Text("完成 — 共 ${phase.recordCount} 筆紀錄, ${formatSize(phase.byteCount)}.")
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

// ============================================================
// Export dialog (含首次設定恢復碼流程)
// ============================================================

@Composable
private fun ExportDialog(
    pendingCode: String?,
    vm: BackupViewModel,
    onDismiss: () -> Unit,
) {
    // 三步: 顯示恢復碼 → 驗證 retype → SAF CreateDocument
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
            // 首次: 產一組
            if (pendingCode == null) {
                LaunchedEffectOnce { vm.generateRecoveryCode() }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("你的恢復碼") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("請抄寫下面 52 個字元(分成 13 組). 換裝置或重灌後須輸入這串字元才能還原備份.")
                        Text(
                            pendingCode?.let(vm::grouped) ?: "…",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
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
            // 隨機挑一組讓使用者重打
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

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun LaunchedEffectOnce(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
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
// Import dialog
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

    androidx.compose.runtime.LaunchedEffect(step) {
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
                        label = { Text("52 字元(空白/連字號會被忽略)") },
                    )
                    HorizontalDivider()
                    Text("合併模式")
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == BackupManager.ImportMode.Merge,
                            onClick = { mode = BackupManager.ImportMode.Merge },
                        )
                        Text("合併到本機(預設, 各筆紀錄走 LWW)")
                    }
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
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
// View recovery dialog
// ============================================================

@Composable
private fun ViewRecoveryDialog(
    code: String,
    grouped: String,
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
                    Text(
                        grouped,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
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
