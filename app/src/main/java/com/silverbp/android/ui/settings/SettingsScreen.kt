package com.silverbp.android.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
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
import com.silverbp.android.ui.chat.CHAT_SYSTEM_PERSONA
import com.silverbp.android.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val modelPhase by ServiceLocator.modelLoadStatus.phase.collectAsStateWithLifecycle()
    var hfToken by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val hcReadPerms = remember { ServiceLocator.healthConnectExerciseBridge.readPermissions }
    // On Android 14+ (UPSIDE_DOWN_CAKE) Health Connect is OS-integrated and
    // its permissions are real Android runtime permissions — request them via
    // the standard contract. The HC SDK 1.1.0-alpha07 contract still launches
    // the legacy `androidx.health.ACTION_REQUEST_PERMISSIONS` action which has
    // no handler on built-in HC (silent no-op).
    val hcModernLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> vm.onHealthConnectGrantResult(results.filterValues { it }.keys) }
    val hcLegacyLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> vm.onHealthConnectGrantResult(granted) }

    LaunchedEffect(Unit) {
        vm.hcPermissionDenied.collect {
            val result = snackbarHostState.showSnackbar(
                message = "Health Connect 權限被拒,請手動授予「步數讀取」權限",
                actionLabel = "開啟 Health Connect",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                runCatching {
                    val intent = android.content.Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.recognitionBackend == RecognitionBackend.AICore,
                        onClick = { vm.setRecognitionBackend(RecognitionBackend.AICore) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(
                            "AICore (Gemini Nano,Pixel 9/10 only)",
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "系統內建 Gemini Nano + Tensor TPU 加速。模型由 Android 管理,APK 不增重。",
                            style = MaterialTheme.typography.bodySmall,
                        )
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

            // ===== AICore (Gemini Nano) configuration =====
            if (state.recognitionBackend == RecognitionBackend.AICore) {
                SectionCard("AICore (Gemini Nano)") {
                    Text(
                        "在 Pixel 9/10 系列上由 Android 系統服務 (AICore) 載入 Gemini Nano,使用 Tensor TPU 加速。" +
                            "首次載入可能需從 Play 服務下載模型。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(8.dp))
                    val socHint = remember { DeviceCapabilities.currentSocModel() }
                    if (socHint.isNotEmpty()) {
                        Text(
                            "本機 SoC: $socHint",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.size(6.dp))
                    when (val phase = modelPhase) {
                        is ModelLoadPhase.Downloading -> {
                            val pct = (phase.fraction * 100f).toInt().coerceIn(0, 100)
                            LinearProgressIndicator(
                                progress = { phase.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Gemini Nano 下載中… $pct%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        is ModelLoadPhase.Loading -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "正在 warm up…",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        is ModelLoadPhase.Ready -> {
                            Text(
                                "已載入,可用。",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        is ModelLoadPhase.Failed -> {
                            Text(
                                "載入失敗:${phase.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        ModelLoadPhase.Idle -> {
                            Text(
                                "尚未載入。按下方按鈕檢查並下載 Gemini Nano。",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = { ModelBootstrap.preloadAICore(context) },
                        enabled = !ServiceLocator.modelLoadStatus.isBusy,
                    ) { Text("檢查 / 下載 Gemini Nano") }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "若顯示「unavailable」表示這台裝置或 Play 服務還沒提供 Gemini Nano。" +
                            "請在系統設定 → 系統 → 開發人員選項 → AICore Settings 確認模型已啟用," +
                            "並把帳號加入 aicore-experimental Google Group + Play Store tester 計畫。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ===== Chat settings (applies to all backends) =====
            SectionCard("聊天設定") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "聊天 persona",
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.setChatPersona("") }) { Text("重置") }
                }
                Text(
                    "留空 = 使用內建預設 persona。下一輪訊息即套用,不需重新載入模型。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(4.dp))
                OutlinedTextField(
                    value = state.chatPersona,
                    onValueChange = { vm.setChatPersona(it) },
                    placeholder = { Text(CHAT_SYSTEM_PERSONA.take(120) + "…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 12,
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                ToggleRow(
                    label = "包含個人健康紀錄",
                    checked = state.chatIncludeRecordsContext,
                    onChange = vm::setChatIncludeRecordsContext,
                )
                Text(
                    "啟用時會把最新血壓 / 7 日 30 日統計 / 運動 / 徽章摘要附在 system prompt 中讓 LLM 參考。" +
                        "關閉適合測試模型對中性問題的回應。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Coach
            SectionCard(stringResource(R.string.coach_settings_section)) {
                ToggleRow(
                    label = stringResource(R.string.coach_settings_enable),
                    checked = state.enableCoach,
                    onChange = vm::setEnableCoach,
                )
                Text(
                    stringResource(R.string.coach_settings_enable_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.enableCoach) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SleepTrackingRow(
                        enabled = state.sleepTrackingEnabled,
                        onEnable = { vm.onSleepGrantResult(it) },
                        onDisable = vm::disableSleepTracking,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    DietTrackingRow(
                        enabled = state.dietTrackingEnabled,
                        onEnable = { vm.onDietGrantResult(it) },
                        onDisable = vm::disableDietTracking,
                    )
                }
            }

            // Integrations
            SectionCard(stringResource(R.string.integration_section)) {
                ToggleRow(
                    label = stringResource(R.string.health_connect),
                    checked = state.enableHealthConnect,
                    onChange = { newValue ->
                        if (newValue) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                hcModernLauncher.launch(hcReadPerms.toTypedArray())
                            } else {
                                hcLegacyLauncher.launch(hcReadPerms)
                            }
                        } else vm.disableHealthConnect()
                    },
                )
                HorizontalDivider()
                ToggleRow(
                    label = stringResource(R.string.cloud_sync) + "  " + stringResource(R.string.cloud_sync_unavailable),
                    checked = state.enableCloudSync,
                    onChange = vm::setCloudSync,
                    enabled = false,
                )
            }

            // Daily step goal + medal notifications
            SectionCard(stringResource(R.string.medal_settings_section)) {
                StepGoalRow(
                    goal = state.dailyStepGoal,
                    onChange = vm::setDailyStepGoal,
                )
                HorizontalDivider()
                ToggleRow(
                    label = stringResource(R.string.medal_settings_notify),
                    checked = state.notifyOnMedalUnlock,
                    onChange = vm::setNotifyOnMedalUnlock,
                )
                NotificationPermissionHint()
            }

            // Location permission status (Exercise feature)
            SectionCard(stringResource(R.string.exercise_settings_section)) {
                LocationPermissionRow()
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
private fun SleepTrackingRow(
    enabled: Boolean,
    onEnable: (Set<String>) -> Unit,
    onDisable: () -> Unit,
) {
    val ctx = LocalContext.current
    val perms = remember { ServiceLocator.healthConnectBridge.sleepReadPermissions }
    // Modern (Android 14+): HC permissions are real Android runtime perms.
    // Older: route through HC SDK's legacy contract. Mirrors HC steps wiring.
    val modernLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> onEnable(results.filterValues { it }.keys) }
    val legacyLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> onEnable(granted) }

    ToggleRow(
        label = stringResource(R.string.coach_settings_sleep_tracking),
        checked = enabled,
        onChange = { newValue ->
            if (newValue) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    modernLauncher.launch(perms.toTypedArray())
                } else {
                    legacyLauncher.launch(perms)
                }
            } else onDisable()
        },
    )
    Text(
        stringResource(R.string.coach_settings_sleep_tracking_hint),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DietTrackingRow(
    enabled: Boolean,
    onEnable: (Set<String>) -> Unit,
    onDisable: () -> Unit,
) {
    val ctx = LocalContext.current
    val perms = remember { ServiceLocator.healthConnectBridge.nutritionReadPermissions }
    val modernLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> onEnable(results.filterValues { it }.keys) }
    val legacyLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> onEnable(granted) }

    ToggleRow(
        label = stringResource(R.string.coach_settings_diet_tracking),
        checked = enabled,
        onChange = { newValue ->
            if (newValue) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    modernLauncher.launch(perms.toTypedArray())
                } else {
                    legacyLauncher.launch(perms)
                }
            } else onDisable()
        },
    )
    Text(
        stringResource(R.string.coach_settings_diet_tracking_hint),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun StepGoalRow(goal: Int, onChange: (Int) -> Unit) {
    val numberFormat = remember { java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.medal_settings_goal), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.medal_settings_goal_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(
            onClick = { onChange((goal - 1000).coerceAtLeast(2_000)) },
            enabled = goal > 2_000,
        ) { Text("−1000") }
        Text(
            numberFormat.format(goal),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        TextButton(
            onClick = { onChange((goal + 1000).coerceAtMost(30_000)) },
            enabled = goal < 30_000,
        ) { Text("+1000") }
    }
}

@Composable
private fun NotificationPermissionHint() {
    val context = LocalContext.current
    val granted = com.silverbp.android.achievements.MedalNotifier.hasPostPermission(context)
    if (granted) return
    Spacer(Modifier.size(4.dp))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.medal_settings_notify_denied),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        }) {
            Text(stringResource(R.string.exercise_settings_open_app_settings))
        }
    }
}

@Composable
private fun LocationPermissionRow() {
    val context = LocalContext.current
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.exercise_settings_gps_label), fontWeight = FontWeight.Medium)
            Text(
                stringResource(
                    if (granted) R.string.exercise_settings_gps_granted
                    else R.string.exercise_settings_gps_denied
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!granted) {
            TextButton(onClick = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null),
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            }) {
                Text(stringResource(R.string.exercise_settings_open_app_settings))
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
