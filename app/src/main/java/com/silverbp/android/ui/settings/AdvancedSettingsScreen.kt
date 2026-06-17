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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.silverbp.android.ui.chat.CHAT_SYSTEM_PERSONA
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.launch

/**
 * Technical AI / model configuration split out of the main Settings screen so
 * the everyday screen stays uncluttered for elderly users. Reuses the same
 * [SettingsViewModel] (a fresh instance here, but both back onto the singleton
 * UserSettingsRepository, so changes persist and reflect on the main screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onClose: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    val modelPhase by ServiceLocator.modelLoadStatus.phase.collectAsStateWithLifecycle()
    var hfToken by remember { mutableStateOf("") }
    // Bumped after a model delete so each ModelVariantRow re-reads the disk and
    // drops its "Downloaded" chip (file existence isn't a reactive State).
    var modelsRefresh by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ModelVariant?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_advanced_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            // ===== Recognition backend (Local / Cloud / AICore) =====
            StandardCard(title = stringResource(R.string.settings_recognition_section)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.recognitionBackend == RecognitionBackend.Local,
                        onClick = { vm.setRecognitionBackend(RecognitionBackend.Local) },
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Column {
                        Text(stringResource(R.string.settings_backend_local_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.settings_backend_local_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.recognitionBackend == RecognitionBackend.Cloud,
                        onClick = { vm.setRecognitionBackend(RecognitionBackend.Cloud) },
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Column {
                        Text(stringResource(R.string.settings_backend_cloud_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.settings_backend_cloud_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.recognitionBackend == RecognitionBackend.AICore,
                        onClick = { vm.setRecognitionBackend(RecognitionBackend.AICore) },
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Column {
                        Text(
                            stringResource(R.string.settings_backend_aicore_title),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.settings_backend_aicore_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // ===== Local model picker (when Local backend active) =====
            if (state.recognitionBackend == RecognitionBackend.Local) {
                StandardCard(title = stringResource(R.string.settings_local_model_section)) {
                    val downloader = remember { ModelDownloader(context) }
                    ModelCatalog.variants.forEach { variant ->
                        val isSelected = state.selectedModelId == variant.id
                        val isDownloaded = remember(variant.id, modelPhase, modelsRefresh) {
                            downloader.isDownloaded(variant)
                        }
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
                            onDelete = { pendingDelete = variant },
                            canDelete = !ServiceLocator.modelLoadStatus.isBusy,
                            inProgress = rowDownloadFraction != null || rowLoading,
                            downloadFraction = rowDownloadFraction,
                        )
                        HorizontalDivider()
                    }

                    (modelPhase as? ModelLoadPhase.Failed)?.let { failed ->
                        Spacer(Modifier.size(AppSpacing.tight))
                        Text(
                            stringResource(R.string.model_failed, failed.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    OutlinedTextField(
                        value = hfToken,
                        onValueChange = { hfToken = it },
                        label = { Text(stringResource(R.string.settings_hf_token_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        stringResource(R.string.settings_hf_token_help),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = AppSpacing.tight),
                    )
                }

                StandardCard(title = stringResource(R.string.settings_model_tuning_section)) {
                    Text(stringResource(R.string.settings_max_tokens_label), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.settings_max_tokens_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(AppSpacing.tight))
                    MaxTokensDropdown(
                        selected = state.maxNumTokens,
                        onSelect = { vm.setMaxNumTokens(it) },
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Button(
                        onClick = { ModelBootstrap.reloadCurrentVariant(context) },
                        enabled = !ServiceLocator.modelLoadStatus.isBusy,
                    ) { Text(stringResource(R.string.settings_apply_reload)) }

                    HorizontalDivider(Modifier.padding(vertical = AppSpacing.itemGap))

                    Text(stringResource(R.string.settings_vision_backend_label), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.settings_vision_backend_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(AppSpacing.tight))
                    val socHint = remember { DeviceCapabilities.currentSocModel() }
                    VisionBackendDropdown(
                        selected = state.visionBackendOverride,
                        socHint = socHint,
                        onSelect = { vm.setVisionBackendOverride(it) },
                    )

                    HorizontalDivider(Modifier.padding(vertical = AppSpacing.itemGap))

                    val selectedVariant = remember(state.selectedModelId) {
                        ModelCatalog.byId(state.selectedModelId)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_speculative_label), fontWeight = FontWeight.Medium)
                            Text(
                                if (selectedVariant.supportsSpeculativeDecoding) {
                                    stringResource(R.string.settings_speculative_supported_help)
                                } else {
                                    stringResource(
                                        R.string.settings_speculative_unsupported_help,
                                        stringResource(selectedVariant.displayNameRes),
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.enableSpeculativeDecoding,
                            onCheckedChange = { vm.setEnableSpeculativeDecoding(it) },
                            enabled = selectedVariant.supportsSpeculativeDecoding,
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = AppSpacing.itemGap))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings_ocr_prompt_label),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.setSystemPrompt("") }) {
                            Text(stringResource(R.string.settings_action_reset))
                        }
                    }
                    Text(
                        stringResource(R.string.settings_ocr_prompt_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(AppSpacing.tight))
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
                StandardCard(title = stringResource(R.string.settings_gemini_section)) {
                    OutlinedTextField(
                        value = state.geminiApiKey,
                        onValueChange = { vm.setGeminiApiKey(it) },
                        label = { Text(stringResource(R.string.settings_gemini_api_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    GeminiModelDropdown(
                        selected = state.geminiModel,
                        onSelect = { vm.setGeminiModel(it) },
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Text(
                        stringResource(R.string.settings_gemini_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ===== AICore (Gemini Nano) configuration =====
            if (state.recognitionBackend == RecognitionBackend.AICore) {
                StandardCard(title = stringResource(R.string.settings_aicore_section)) {
                    Text(
                        stringResource(R.string.settings_aicore_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    val socHint = remember { DeviceCapabilities.currentSocModel() }
                    if (socHint.isNotEmpty()) {
                        Text(
                            stringResource(R.string.settings_aicore_soc, socHint),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.size(AppSpacing.tight))
                    when (val phase = modelPhase) {
                        is ModelLoadPhase.Downloading -> {
                            val pct = (phase.fraction * 100f).toInt().coerceIn(0, 100)
                            LinearProgressIndicator(
                                progress = { phase.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(R.string.settings_aicore_downloading, pct),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = AppSpacing.tight),
                            )
                        }
                        is ModelLoadPhase.Loading -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                stringResource(R.string.settings_aicore_warming),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = AppSpacing.tight),
                            )
                        }
                        is ModelLoadPhase.Ready -> {
                            Text(
                                stringResource(R.string.settings_aicore_ready),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        is ModelLoadPhase.Failed -> {
                            Text(
                                stringResource(R.string.settings_aicore_failed, phase.message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        ModelLoadPhase.Idle -> {
                            Text(
                                stringResource(R.string.settings_aicore_idle),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Button(
                        onClick = { ModelBootstrap.preloadAICore(context) },
                        enabled = !ServiceLocator.modelLoadStatus.isBusy,
                    ) { Text(stringResource(R.string.settings_aicore_check_button)) }
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Text(
                        stringResource(R.string.settings_aicore_unavailable_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ===== Chat persona (system-prompt override) =====
            StandardCard(title = stringResource(R.string.settings_chat_section)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_chat_persona_label),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.setChatPersona("") }) {
                        Text(stringResource(R.string.settings_action_reset))
                    }
                }
                Text(
                    stringResource(R.string.settings_chat_persona_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(AppSpacing.tight))
                OutlinedTextField(
                    value = state.chatPersona,
                    onValueChange = { vm.setChatPersona(it) },
                    placeholder = { Text(CHAT_SYSTEM_PERSONA.take(120) + "…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 12,
                )
            }
        }

        pendingDelete?.let { variant ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.model_delete_dialog_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.model_delete_dialog_message,
                            stringResource(variant.displayNameRes),
                            variant.approxSizeGB,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        scope.launch {
                            ModelBootstrap.deleteVariant(context, variant)
                            modelsRefresh++
                        }
                    }) {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
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
    onDelete: () -> Unit,
    canDelete: Boolean,
    inProgress: Boolean,
    downloadFraction: Float? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.tight)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = onSelect, enabled = isDownloaded)
            Spacer(Modifier.size(AppSpacing.itemGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(variant.displayNameRes), fontWeight = FontWeight.Medium)
                Text(
                    "%.2f GB · %s".format(variant.approxSizeGB, stringResource(variant.notesRes)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (isDownloaded) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                stringResource(R.string.model_chip_downloaded),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
            if (!isDownloaded) {
                TextButton(onClick = onDownload, enabled = !inProgress) {
                    Text(
                        stringResource(
                            if (inProgress) R.string.model_button_downloading
                            else R.string.model_button_download,
                        ),
                    )
                }
            } else {
                TextButton(onClick = onDelete, enabled = canDelete) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (downloadFraction != null) {
            val pct = (downloadFraction * 100f).toInt().coerceIn(0, 100)
            Spacer(Modifier.size(AppSpacing.tight))
            LinearProgressIndicator(
                progress = { downloadFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.model_download_progress, pct),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else if (inProgress) {
            Spacer(Modifier.size(AppSpacing.tight))
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
            label = { Text(stringResource(R.string.settings_gemini_model_label)) },
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
            label = { Text(stringResource(R.string.settings_max_tokens_dropdown)) },
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
            label = { Text(stringResource(R.string.settings_vision_backend_dropdown)) },
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
