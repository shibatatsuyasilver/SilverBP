package com.silverbp.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.window.Dialog
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.BuildConfig
import com.silverbp.android.R
import com.silverbp.android.coach.DayOfWeekMask
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.AppLanguage
import com.silverbp.android.settings.AppThemeMode
import com.silverbp.android.settings.LocaleHelper
import com.silverbp.android.ui.coach.formatTime
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit = {},
    onOpenSyncPairing: () -> Unit = {},
    onOpenManageMedications: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val appLockStatus by vm.appLockStatus.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    // Core Health Connect permissions the master toggle requests in one sheet:
    //   • steps READ      — medals / step backfill
    //   • exercise WRITE  — finished workouts (+route) mirror to HC
    //   • blood-pressure WRITE — readings flow into Google Health / other apps
    // Sleep & diet reads stay behind their own Coach toggles further down.
    val hcCorePerms = remember {
        ServiceLocator.healthConnectExerciseBridge.readPermissions +
            ServiceLocator.healthConnectExerciseBridge.permissions +
            ServiceLocator.healthConnectBpBridge.permissions +
            // 飲食: mirror logged meals into Health Connect as NutritionRecord.
            ServiceLocator.healthConnectNutritionBridge.permissions +
            // Android 15+: the background step-sync worker reads nothing without this.
            ServiceLocator.healthConnectBridge.backgroundReadPermissions
    }
    // 1.1.0's request contract is platform-aware: it delegates to the built-in
    // Health Connect module on Android 14+ and the standalone HC app on
    // Android 13, so one launcher covers both. (The old dual-launcher hack
    // existed only because alpha07's contract was a silent no-op on built-in HC.)
    val hcLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> vm.onHealthConnectGrantResult(granted) }

    val hcStepsDeniedMsg = stringResource(R.string.settings_hc_steps_denied)
    val hcOpenAction = stringResource(R.string.settings_hc_open_action)
    LaunchedEffect(Unit) {
        vm.hcPermissionDenied.collect {
            val result = snackbarHostState.showSnackbar(
                message = hcStepsDeniedMsg,
                actionLabel = hcOpenAction,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            // Appearance — app color theme (System / Light / Dark)
            StandardCard(title = stringResource(R.string.settings_appearance_title)) {
                AppThemeMode.entries.forEach { mode ->
                    val labelRes = when (mode) {
                        AppThemeMode.System -> R.string.settings_theme_system
                        AppThemeMode.Light -> R.string.settings_theme_light
                        AppThemeMode.Dark -> R.string.settings_theme_dark
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.appThemeMode == mode,
                            onClick = { vm.setAppThemeMode(mode) },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(labelRes))
                    }
                }
            }

            // App language — framework per-app locale (System / English / 繁體中文).
            // The value is read from LocaleManager, not `state` (it isn't persisted
            // in DataStore); selecting one recreates the Activity so this recomposes
            // with the new current language.
            StandardCard(title = stringResource(R.string.settings_language_section)) {
                val currentLang = LocaleHelper.current(context)
                AppLanguage.entries.forEach { lang ->
                    val labelRes = when (lang) {
                        AppLanguage.System -> R.string.settings_language_system
                        AppLanguage.English -> R.string.settings_language_english
                        AppLanguage.TraditionalChinese -> R.string.settings_language_zh_tw
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentLang == lang,
                            onClick = { vm.setAppLanguage(context, lang) },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(labelRes))
                    }
                }
            }

            // Personalization — coach nickname (mirrors onboarding capture).
            StandardCard(title = stringResource(R.string.settings_personalization_section)) {
                Text(
                    stringResource(R.string.settings_user_nickname_label),
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(4.dp))
                OutlinedTextField(
                    value = state.userNickname,
                    onValueChange = { vm.setUserNickname(it) },
                    placeholder = {
                        Text(stringResource(R.string.settings_user_nickname_placeholder))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.settings_user_nickname_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Hypertension guideline
            StandardCard(title = stringResource(R.string.guideline_section)) {
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

            // ===== Chat settings (applies to all backends) =====
            StandardCard(title = stringResource(R.string.settings_chat_section)) {
                ToggleRow(
                    label = stringResource(R.string.settings_chat_include_records),
                    checked = state.chatIncludeRecordsContext,
                    onChange = vm::setChatIncludeRecordsContext,
                )
                Text(
                    stringResource(R.string.settings_chat_include_records_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Coach
            StandardCard(title = stringResource(R.string.coach_settings_section)) {
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
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    // Medication management entry point: the Coach tab's
                    // medication module card only appears once at least one
                    // medication has an active schedule, so without this row
                    // a first-time user has no UI path to add medication #1.
                    Button(
                        onClick = onOpenManageMedications,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_manage_medications_button)) }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.settings_manage_medications_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Coach daily reminder — time + weekday selection. Defaults reproduce
            // the legacy daily-07:00 behaviour. Only meaningful while Coach is on.
            if (state.enableCoach) {
                StandardCard(title = stringResource(R.string.coach_reminder_section)) {
                    ToggleRow(
                        label = stringResource(R.string.coach_reminder_enable),
                        checked = state.reminderEnabled,
                        onChange = vm::setReminderEnabled,
                    )
                    Text(
                        stringResource(R.string.coach_reminder_enable_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.reminderEnabled) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        ReminderTimeRow(
                            hour = state.reminderHour,
                            minute = state.reminderMinute,
                            onChange = { h, m -> vm.setReminderTime(h, m) },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        ReminderDaysRow(
                            mask = state.reminderDaysMask,
                            onChange = vm::setReminderDaysMask,
                        )
                    }
                }
            }

            // Integrations
            StandardCard(title = stringResource(R.string.integration_section)) {
                ToggleRow(
                    label = stringResource(R.string.health_connect),
                    checked = state.enableHealthConnect,
                    onChange = { newValue ->
                        if (newValue) hcLauncher.launch(hcCorePerms)
                        else vm.disableHealthConnect()
                    },
                )
            }

            // Daily step goal + medal notifications
            StandardCard(title = stringResource(R.string.medal_settings_section)) {
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
            StandardCard(title = stringResource(R.string.exercise_settings_section)) {
                LocationPermissionRow()
            }

            // Cross-device sync — opens the QR-pairing flow for adding a paired
            // iPhone/Android peer over LAN. End-to-end Noise XK + SAS confirm.
            // v1.0 隱藏配對入口：持續同步與 LWW 合併尚未完成，正式版先不開放，
            // 僅在開發版 (BuildConfig.DEBUG) 顯示以便繼續開發。
            if (BuildConfig.DEBUG) {
                StandardCard(title = stringResource(R.string.settings_sync_section)) {
                    Button(
                        onClick = onOpenSyncPairing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_sync_pair_button)) }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.settings_sync_pair_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Backup / Restore — encrypted .sbpbk snapshot export/import to
            // user's chosen storage (Google Drive, iCloud, local file).
            // Survives uninstall; cross-device + cross-platform with iOS BPCoach.
            StandardCard(title = stringResource(R.string.backup_screen_title)) {
                Button(
                    onClick = onOpenBackup,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_backup_button_label)) }
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.settings_backup_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Security — opt-in biometric/device-credential lock + at-rest
            // encryption. Default off; enabling runs the DB migration.
            StandardCard(title = stringResource(R.string.settings_security_section)) {
                ToggleRow(
                    label = stringResource(R.string.settings_app_lock_label),
                    checked = state.appLockEnabled,
                    onChange = { vm.setAppLock(it) },
                    enabled = appLockStatus != SettingsViewModel.AppLockStatus.Working,
                )
                Text(
                    stringResource(R.string.settings_app_lock_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                when (appLockStatus) {
                    SettingsViewModel.AppLockStatus.Working -> {
                        Spacer(Modifier.size(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.settings_app_lock_working),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    SettingsViewModel.AppLockStatus.NeedsDeviceCredential -> {
                        Text(
                            stringResource(R.string.settings_app_lock_need_credential),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_SECURITY_SETTINGS,
                                    ).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                            vm.dismissAppLockStatus()
                        }) { Text(stringResource(R.string.settings_app_lock_open_security)) }
                    }
                    is SettingsViewModel.AppLockStatus.Failed -> {
                        Text(
                            stringResource(R.string.settings_app_lock_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    SettingsViewModel.AppLockStatus.Idle -> Unit
                }
            }

            // Advanced — recognition engine / AI models / prompts live in a
            // sub-screen to keep this everyday screen uncluttered.
            StandardCard(title = stringResource(R.string.settings_advanced_screen_title)) {
                Button(
                    onClick = onOpenAdvanced,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_advanced_entry_button)) }
            }

            // About
            StandardCard(title = stringResource(R.string.about_section)) {
                Text(stringResource(R.string.about_model), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.not_medical_device), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(BuildConfig.PRIVACY_POLICY_URL),
                            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_privacy_policy))
                }
                TextButton(
                    onClick = { vm.reviewConsent() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_review_consent))
                }
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
    val perms = remember {
        ServiceLocator.healthConnectBridge.sleepReadPermissions +
            // Android 15+: lets the background sleep-backfill worker read.
            ServiceLocator.healthConnectBridge.backgroundReadPermissions
    }
    // Single platform-aware contract (see master toggle): one launcher covers
    // the built-in HC module (Android 14+) and the standalone HC app (13).
    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> onEnable(granted) }

    ToggleRow(
        label = stringResource(R.string.coach_settings_sleep_tracking),
        checked = enabled,
        onChange = { newValue -> if (newValue) launcher.launch(perms) else onDisable() },
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
    val perms = remember {
        ServiceLocator.healthConnectBridge.nutritionReadPermissions +
            // Android 15+: lets the background nutrition-backfill worker read.
            ServiceLocator.healthConnectBridge.backgroundReadPermissions
    }
    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> onEnable(granted) }

    ToggleRow(
        label = stringResource(R.string.coach_settings_diet_tracking),
        checked = enabled,
        onChange = { newValue -> if (newValue) launcher.launch(perms) else onDisable() },
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeRow(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.coach_reminder_time_label),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { showPicker = true }) {
            Text(formatTime(hour, minute))
        }
    }
    if (showPicker) {
        val pickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showPicker = false }) {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TimePicker(state = pickerState)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showPicker = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = {
                            onChange(pickerState.hour, pickerState.minute)
                            showPicker = false
                        }) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDaysRow(mask: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.coach_reminder_days_label), fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.values().forEach { dow ->
                FilterChip(
                    selected = DayOfWeekMask.contains(mask, dow),
                    onClick = { onChange(DayOfWeekMask.toggle(mask, dow)) },
                    label = {
                        Text(dow.getDisplayName(TextStyle.SHORT, java.util.Locale.getDefault()))
                    },
                )
            }
        }
        if (DayOfWeekMask.isEmpty(mask)) {
            Text(
                stringResource(R.string.coach_reminder_days_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
