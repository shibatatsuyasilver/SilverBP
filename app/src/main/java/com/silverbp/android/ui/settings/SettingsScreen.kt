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
import com.silverbp.android.recognition.GeminiCloudRecognizer
import com.silverbp.android.recognition.ModelBootstrap
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.ModelDownloader
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.ModelVariant
import com.silverbp.android.recognition.RecognitionBackend

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
                            inProgress = (modelPhase is ModelLoadPhase.Downloading
                                || modelPhase is ModelLoadPhase.Loading) && isSelected,
                        )
                        HorizontalDivider()
                    }

                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.tab_settings).let { "下載狀態" }, style = MaterialTheme.typography.labelMedium)
                    val statusText = when (val p = modelPhase) {
                        ModelLoadPhase.Idle -> stringResource(R.string.model_idle)
                        is ModelLoadPhase.Downloading -> stringResource(R.string.model_downloading, (p.fraction * 100).toInt())
                        ModelLoadPhase.Loading -> stringResource(R.string.model_loading)
                        ModelLoadPhase.Ready -> stringResource(R.string.model_ready)
                        is ModelLoadPhase.Failed -> stringResource(R.string.model_failed, p.message)
                    }
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    if (modelPhase is ModelLoadPhase.Downloading) {
                        LinearProgressIndicator(
                            progress = { (modelPhase as ModelLoadPhase.Downloading).fraction },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
