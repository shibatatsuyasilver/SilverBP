package com.silverbp.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.BpPrompt
import com.silverbp.android.recognition.DeviceCapabilities
import com.silverbp.android.recognition.GeminiCloudRecognizer
import com.silverbp.android.recognition.ModelBootstrap
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.ModelDownloader
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.ModelVariant
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.VisionBackendOverride

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val modelPhase by ServiceLocator.modelLoadStatus.phase.collectAsStateWithLifecycle()
    var hfToken by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hypertension guideline
            SectionCard(stringResource(R.string.guideline_section)) {
                HypertensionGuideline.entries.forEach { g ->
                    val labelRes = when (g) {
                        HypertensionGuideline.Taiwan2022 -> R.string.guideline_taiwan2022
                        HypertensionGuideline.AccAha2017 -> R.string.guideline_acc_aha_2017
                        HypertensionGuideline.Esh2023 -> R.string.guideline_esh_2023
                        HypertensionGuideline.Jnc8 -> R.string.guideline_jnc_8
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.guideline == g, onClick = { vm.setGuideline(g) })
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(labelRes))
                    }
                }
            }

            // ===== Recognition backend (Local / Cloud) =====
            SectionCard("辨識引擎") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.recognitionBackend == RecognitionBackend.Local,
                        onClick = { vm.setRecognitionBackend(RecognitionBackend.Local) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("本機模型 (LiteRT-LM)", fontWeight = FontWeight.Medium)
                        Text("離線運作,無 API 費用,首次需下載", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.recognitionBackend == RecognitionBackend.Cloud,
                        onClick = { vm.setRecognitionBackend(RecognitionBackend.Cloud) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("雲端 API (Google Gemini)", fontWeight = FontWeight.Medium)
                        Text("不佔本機 RAM,需 API key + 網路", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ===== Local model picker (when Local backend active) =====
            if (state.recognitionBackend == RecognitionBackend.Local) {
                SectionCard("選擇本機模型") {
                    val downloader = remember { ModelDownloader(context) }
                    ModelCatalog.variants.forEach { variant ->
                        val isSelected = state.selectedModelId == variant.id
                        val isDownloaded = downloader.isDownloaded(variant)
                        // Surface progress on the row whose variant is actually downloading,
                        // not just the currently-selected one (user often taps 下載 on a
                        // non-selected variant first).
                        val rowDownloadFraction = (modelPhase as? ModelLoadPhase.Downloading)
                            ?.takeIf { it.variantId == variant.id || (it.variantId == null && isSelected) }
                            ?.fraction
                        val rowLoading = modelPhase is ModelLoadPhase.Loading && isSelected
                        ModelVariantRow(
                            variant = variant,
                            isSelected = isSelected,
                            isDownloaded = isDownloaded,
                            onSelect = {
                                vm.setSelectedModelId(variant.id)
                                ModelBootstrap.switchTo(context, variant)
                            },
                            onDownload = {
                                ModelBootstrap.downloadAndPreload(context, variant, hfToken.takeIf { it.isNotBlank() })
                            },
                            inProgress = rowDownloadFraction != null || rowLoading,
                            downloadFraction = rowDownloadFraction,
                        )
                        HorizontalDivider()
                    }

                    // Failure surfaces inline so the user knows why a load broke,
                    // but routine status (Idle/Loading/Ready) is conveyed by the row's
                    // own "已下載"/"下載中…" + per-row progress bar.
                    (modelPhase as? ModelLoadPhase.Failed)?.let { failed ->
                        Spacer(Modifier.size(6.dp))
                        Text(
                            stringResource(R.string.model_failed, failed.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    OutlinedTextField(
                        value = hfToken,
                        onValueChange = { hfToken = it },
                        label = { Text("Hugging Face token (gated 模型才需要)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "Gemma 模型在 HF 受授權保護:登入 huggingface.co → 接受 Gemma 條款 → Settings → Access Tokens 產一個 read token。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                SectionCard("進階設定") {
                    Text("Max tokens (KV cache)", fontWeight = FontWeight.Medium)
                    Text(
                        "影響 GPU 記憶體用量與輸出長度。改值後請按下方按鈕重新載入模型。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(4.dp))
                    MaxTokensDropdown(
                        selected = state.maxNumTokens,
                        onSelect = { vm.setMaxNumTokens(it) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = { ModelBootstrap.reloadCurrentVariant(context) },
                        enabled = !ServiceLocator.modelLoadStatus.isBusy,
                    ) { Text("套用並重新載入模型") }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))

                    Text("Vision encoder backend", fontWeight = FontWeight.Medium)
                    Text(
                        "Auto = 依 SoC 自動選擇 (Adreno 7xx → CPU,其餘 → GPU + 失敗自動 fallback)。" +
                            "改值後請按上方「套用並重新載入模型」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(4.dp))
                    val socHint = remember { DeviceCapabilities.currentSocModel() }
                    VisionBackendDropdown(
                        selected = state.visionBackendOverride,
                        socHint = socHint,
                        onSelect = { vm.setVisionBackendOverride(it) },
                    )

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "OCR system prompt",
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.setSystemPrompt("") }) { Text("重置") }
                    }
                    Text(
                        "留空 = 使用內建預設 prompt。下一張照片即套用,不需重新載入模型。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(4.dp))
                    OutlinedTextField(
                        value = state.systemPrompt,
                        onValueChange = { vm.setSystemPrompt(it) },
                        placeholder = { Text(BpPrompt.defaultSystem.take(120) + "…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 14,
                    )
                }
            }

            // ===== Cloud (Gemini) configuration =====
            if (state.recognitionBackend == RecognitionBackend.Cloud) {
                SectionCard("Gemini 設定") {
                    OutlinedTextField(
                        value = state.geminiApiKey,
                        onValueChange = { vm.setGeminiApiKey(it) },
                        label = { Text("API Key (AIza…)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.size(8.dp))
                    GeminiModelDropdown(
                        selected = state.geminiModel,
                        onSelect = { vm.setGeminiModel(it) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "至 https://aistudio.google.com/app/apikey 取得 API key。\n" +
                            "Flash 適合大多數情況,Pro 用於模糊或反光的照片。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Integrations
            SectionCard(stringResource(R.string.integration_section)) {
                ToggleRow(stringResource(R.string.health_connect), state.enableHealthConnect, vm::setHealthConnect)
                HorizontalDivider()
                ToggleRow(
                    label = stringResource(R.string.cloud_sync) + "  " + stringResource(R.string.cloud_sync_unavailable),
                    checked = state.enableCloudSync,
                    onChange = vm::setCloudSync,
                    enabled = false,
                )
            }

            // About
            SectionCard(stringResource(R.string.about_section)) {
                Text(stringResource(R.string.about_model), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.not_medical_device), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModelVariantRow(
    variant: ModelVariant,
    isSelected: Boolean,
    isDownloaded: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    inProgress: Boolean,
    downloadFraction: Float? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = onSelect, enabled = isDownloaded)
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(variant.displayName, fontWeight = FontWeight.Medium)
                Text(
                    "%.2f GB · %s".format(variant.approxSizeGB, variant.notes),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (isDownloaded) {
                    AssistChip(
                        onClick = {},
                        label = { Text("已下載", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
            if (!isDownloaded) {
                TextButton(onClick = onDownload, enabled = !inProgress) {
                    Text(if (inProgress) "下載中…" else "下載")
                }
            }
        }
        if (downloadFraction != null) {
            val pct = (downloadFraction * 100f).toInt().coerceIn(0, 100)
            Spacer(Modifier.size(4.dp))
            LinearProgressIndicator(
                progress = { downloadFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "下載進度 $pct%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else if (inProgress) {
            Spacer(Modifier.size(4.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun GeminiModelDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Gemini 模型") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "▲" else "▼") }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GeminiCloudRecognizer.SUPPORTED_MODELS.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun MaxTokensDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(1024, 1536, 2048, 3072, 4096)
    Column {
        OutlinedTextField(
            value = selected.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Max tokens") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "▲" else "▼") }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.toString()) },
                    onClick = { onSelect(v); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun VisionBackendDropdown(
    selected: VisionBackendOverride,
    socHint: String,
    onSelect: (VisionBackendOverride) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = when (selected) {
        VisionBackendOverride.Auto ->
            if (socHint.isNotEmpty()) "Auto (SoC=$socHint)" else "Auto"
        VisionBackendOverride.ForceGPU -> "Force GPU"
        VisionBackendOverride.ForceCPU -> "Force CPU"
    }
    Column {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text("Vision backend") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "▲" else "▼") }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VisionBackendOverride.entries.forEach { v ->
                val label = when (v) {
                    VisionBackendOverride.Auto ->
                        if (socHint.isNotEmpty()) "Auto (SoC=$socHint)" else "Auto"
                    VisionBackendOverride.ForceGPU -> "Force GPU"
                    VisionBackendOverride.ForceCPU -> "Force CPU"
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(v); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
