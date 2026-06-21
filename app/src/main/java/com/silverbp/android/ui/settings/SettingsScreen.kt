package com.silverbp.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.BuildConfig
import com.silverbp.android.R
import com.silverbp.android.coach.DayOfWeekMask
import com.silverbp.android.core.Member
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.AppLanguage
import com.silverbp.android.settings.AppThemeMode
import com.silverbp.android.settings.LocaleHelper
import com.silverbp.android.ui.coach.formatTime
import com.silverbp.android.ui.components.GuidelineStandard
import com.silverbp.android.ui.components.NavListRow
import com.silverbp.android.ui.components.RadioListRow
import com.silverbp.android.ui.components.SettingsDivider
import com.silverbp.android.ui.components.SettingsGroup
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.member.MemberEditorSheet
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.ForgePrimary
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit = {},
    onOpenSyncPairing: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
    onOpenManageMembers: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val appLockStatus by vm.appLockStatus.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Profile editor (per-member profile: age/height/sex/target). Opens the same
    // MemberEditorSheet used by member management, but always for the OWNER so the
    // everyday user has a findable path to their own height/sex/target — the data
    // BMI & targets depend on. The owner row is loaded lazily on tap; the editor
    // only mounts once it's resolved so we never pass a stale/null member.
    val profileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var profileOwner by remember { mutableStateOf<Member?>(null) }
    var showProfileEditor by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    // The standard the user tapped but hasn't confirmed yet; non-null shows the
    // switch-confirmation dialog.
    var pendingStandard by remember { mutableStateOf<GuidelineStandard?>(null) }
    // Core Health Connect permissions the master toggle requests in one sheet:
    //   • steps READ      — medals / step backfill
    //   • exercise WRITE  — finished workouts (+route) mirror to HC
    //   • blood-pressure WRITE — readings flow into Google Health / other apps
    //   • blood-glucose WRITE — glucose readings mirror to HC
    //   • weight WRITE+READ — owner weight mirrors out; smart-scale weight reads in
    // Sleep & diet reads stay behind their own Coach toggles further down.
    val hcCorePerms = remember { coreHealthConnectPermissions() }
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
            SettingsGroup(title = stringResource(R.string.settings_appearance_title)) {
                AppThemeMode.entries.forEachIndexed { index, mode ->
                    val labelRes = when (mode) {
                        AppThemeMode.System -> R.string.settings_theme_system
                        AppThemeMode.Light -> R.string.settings_theme_light
                        AppThemeMode.Dark -> R.string.settings_theme_dark
                    }
                    if (index > 0) SettingsDivider()
                    RadioListRow(
                        title = stringResource(labelRes),
                        selected = state.appThemeMode == mode,
                        onClick = { vm.setAppThemeMode(mode) },
                    )
                }
            }

            // App language — framework per-app locale (System / English / 繁體中文).
            // The value is read from LocaleManager, not `state` (it isn't persisted
            // in DataStore); selecting one recreates the Activity so this recomposes
            // with the new current language.
            SettingsGroup(title = stringResource(R.string.settings_language_section)) {
                val currentLang = LocaleHelper.current(context)
                AppLanguage.entries.forEachIndexed { index, lang ->
                    val labelRes = when (lang) {
                        AppLanguage.System -> R.string.settings_language_system
                        AppLanguage.English -> R.string.settings_language_english
                        AppLanguage.TraditionalChinese -> R.string.settings_language_zh_tw
                    }
                    if (index > 0) SettingsDivider()
                    RadioListRow(
                        title = stringResource(labelRes),
                        selected = currentLang == lang,
                        onClick = { vm.setAppLanguage(context, lang) },
                    )
                }
            }

            // Profile (個人檔案) — per-member age/height/sex/target weight. Lives on
            // the OWNER Member; surfaced here near the top so BMI/target inputs are
            // findable without digging into family-member management. (1) a row that
            // opens MemberEditorSheet for the owner; (2) the coach nickname; (3) the
            // app-wide weight unit.
            SettingsGroup(title = stringResource(R.string.weight_profile_title)) {
                // Tappable nav row → owner profile editor (icon tile + title +
                // subtitle + chevron), mirroring the Today/History entry rows.
                NavListRow(
                    icon = Icons.Filled.Person,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.member_edit_self),
                    subtitle = stringResource(R.string.weight_profile_hint),
                    onClick = {
                        // Resolve the owner first, then mount the editor so the
                        // sheet opens already in edit mode for the right member.
                        scope.launch {
                            profileOwner = ServiceLocator.memberRepository.owner()
                            showProfileEditor = true
                        }
                    },
                )

                SettingsDivider()

                // Coach nickname — how the coach addresses the user (mirrors
                // onboarding capture). Stored in UserSettings (DataStore), so it
                // stays an inline field rather than the member-editor sheet.
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
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

                SettingsDivider()

                // App-wide weight display unit (kg/lb). Canonical storage is kg;
                // this only changes rendering. Mirrors the App-language radio idiom.
                Text(
                    stringResource(R.string.weight_unit_setting),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                RadioListRow(
                    title = stringResource(R.string.weight_unit_kg),
                    selected = state.weightUnit == "kg",
                    onClick = { vm.setWeightUnit("kg") },
                )
                RadioListRow(
                    title = stringResource(R.string.weight_unit_lb),
                    selected = state.weightUnit == "lb",
                    onClick = { vm.setWeightUnit("lb") },
                )
            }

            // Hypertension guideline. The four guidelines collapse into two
            // standards (identical thresholds within each pair), so we show two
            // options. Tapping the *other* standard opens a confirmation dialog
            // (pendingStandard) that spells out the differing threshold and that
            // every reading is re-graded — switching silently would change every
            // reading's category/color with no explanation.
            SettingsGroup(title = stringResource(R.string.guideline_section)) {
                val currentStandard = GuidelineStandard.of(state.guideline)
                GuidelineStandard.entries.forEachIndexed { index, std ->
                    if (index > 0) SettingsDivider()
                    RadioListRow(
                        title = stringResource(std.labelRes),
                        selected = std == currentStandard,
                        onClick = { if (std != currentStandard) pendingStandard = std },
                    )
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
                    SettingsNavRow(
                        icon = Icons.Filled.Sync,
                        title = stringResource(R.string.settings_sync_pair_button),
                        subtitle = stringResource(R.string.settings_sync_pair_help),
                        onClick = onOpenSyncPairing,
                    )
                }
            }

            // Family members (v18) — manage care-recipient profiles. Each member
            // keeps their own BP history / charts / reports; the switcher chip on
            // Today/Data appears only once a second member exists.
            StandardCard(title = stringResource(R.string.member_manage_title)) {
                SettingsNavRow(
                    icon = Icons.Filled.Group,
                    title = stringResource(R.string.member_manage_entry),
                    onClick = onOpenManageMembers,
                )
            }

            // Premium now lives on the prominent Today top-bar crown → full
            // PremiumScreen (replaces the old plain Settings card per owner UX
            // feedback "直接放設定不太好").

            // DEBUG-only simulate-entitlement card (mirrors the DEBUG sync card
            // above). Lets us demo the paywall/gates without published Play
            // products: "Simulate Free" forces isPremium()=false so gates fire.
            if (BuildConfig.DEBUG) {
                DebugEntitlementCard(
                    override = state.debugPremiumOverride,
                    onSelect = { vm.setDebugPremiumOverride(it) },
                )
            }

            // Backup / Restore — encrypted .sbpbk snapshot export/import to
            // user's chosen storage (Google Drive, iCloud, local file).
            // Survives uninstall; cross-device + cross-platform with iOS BPCoach.
            StandardCard(title = stringResource(R.string.backup_screen_title)) {
                SettingsNavRow(
                    icon = Icons.Filled.Backup,
                    title = stringResource(R.string.settings_backup_button_label),
                    subtitle = stringResource(R.string.settings_backup_description),
                    onClick = onOpenBackup,
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
                SettingsNavRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.settings_advanced_entry_button),
                    onClick = onOpenAdvanced,
                )
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
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(BuildConfig.TERMS_POLICY_URL),
                            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_terms_of_service))
                }
                TextButton(
                    onClick = { vm.reviewConsent() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_review_consent))
                }
                TextButton(
                    onClick = { showLicenses = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_open_data_licenses))
                }
            }
        }
    }

    // Confirm a guideline switch and explain its effect before applying it.
    pendingStandard?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingStandard = null },
            title = { Text(stringResource(R.string.guideline_switch_title)) },
            text = {
                Text(
                    stringResource(R.string.guideline_switch_body, target.elevatedThreshold, target.threshold),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setGuideline(target.representative)
                    pendingStandard = null
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStandard = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Open-data attribution (TFDA requires it; ODbL for Open Food Facts).
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(stringResource(R.string.licenses_title)) },
            text = { Text(stringResource(R.string.licenses_body), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    // Per-member profile editor for the owner. Mounted only once the owner row is
    // resolved (profileOwner != null) so it always opens in edit mode.
    if (showProfileEditor) {
        profileOwner?.let { owner ->
            MemberEditorSheet(
                member = owner,
                sheetState = profileSheetState,
                onDismiss = { showProfileEditor = false },
            )
        }
    }
}

/**
 * A tappable navigation row used to group preference entries inside a
 * [StandardCard]: a leading tinted type-icon tile (mirroring the Today/History
 * card idiom), a semibold title with an optional muted subtitle, and a trailing
 * chevron. Senior-friendly — a generous 56.dp min height keeps the whole row a
 * comfortable touch target. Pure UI: the row owns no state and simply forwards
 * [onClick]. The tile tint defaults to the brand purple so every entry reads as
 * the same family unless a caller overrides it.
 */
@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color = ForgePrimary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.cardCorner))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = AppSpacing.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconTile(icon = icon, tint = tint)
        Spacer(Modifier.size(AppSpacing.itemGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(AppSpacing.tight))
        Icon(
            Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The small tinted, rounded icon tile shared by [SettingsNavRow] and the Premium
 * status row — a 46.dp rounded square filled with [tint] at low alpha holding a
 * [tint]-coloured icon. Matches the Today/History tinted type-icon tiles so the
 * whole app's card chrome stays one visual family. Pure UI.
 */
@Composable
private fun SettingsIconTile(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
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

/**
 * DEBUG-only entitlement override selector — 3-way radio
 * (Follow real / Simulate Premium / Simulate Free) writing
 * [SettingsViewModel.setDebugPremiumOverride]. Mirrors the existing DEBUG sync
 * card's idiom. The current [override] string ("premium"/"free"/null) selects
 * the radio. The group carries a contentDescription (audit M31).
 */
@Composable
private fun DebugEntitlementCard(override: String?, onSelect: (String?) -> Unit) {
    val groupCd = stringResource(R.string.debug_entitlement_cd)
    StandardCard(title = stringResource(R.string.debug_entitlement_title)) {
        Column(modifier = Modifier.semantics { contentDescription = groupCd }) {
            DebugEntitlementOption(
                label = stringResource(R.string.debug_entitlement_follow_real),
                selected = override == null,
                onClick = { onSelect(null) },
            )
            DebugEntitlementOption(
                label = stringResource(R.string.debug_entitlement_premium),
                selected = override == "premium",
                onClick = { onSelect("premium") },
            )
            DebugEntitlementOption(
                label = stringResource(R.string.debug_entitlement_free),
                selected = override == "free",
                onClick = { onSelect("free") },
            )
        }
    }
}

@Composable
private fun DebugEntitlementOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(8.dp))
        Text(label)
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
