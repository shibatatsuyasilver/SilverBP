package com.silverbp.android.ui.settings

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.achievements.StepSyncScheduler
import com.silverbp.android.billing.Entitlement
import com.silverbp.android.billing.EntitlementManager
import com.silverbp.android.coach.CoachReminderScheduler
import com.silverbp.android.coach.NutritionBackfillWorker
import com.silverbp.android.coach.SleepBackfillWorker
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.health.BpSyncWorker
import com.silverbp.android.health.ExerciseSyncWorker
import com.silverbp.android.health.GlucoseSyncWorker
import com.silverbp.android.health.WeightSyncWorker
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.VisionBackendOverride
import com.silverbp.android.security.DbCipherMigration
import com.silverbp.android.settings.AppLanguage
import com.silverbp.android.settings.AppThemeMode
import com.silverbp.android.settings.LocaleHelper
import com.silverbp.android.settings.UserSettings
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.ui.lock.canDeviceAuthenticate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ANDROID_15_API = 35

private fun requiredHealthConnectPermissions(): Set<String> =
    ServiceLocator.healthConnectExerciseBridge.readPermissions +
        ServiceLocator.healthConnectExerciseBridge.permissions +
        ServiceLocator.healthConnectBpBridge.permissions +
        ServiceLocator.healthConnectGlucoseBridge.permissions +
        ServiceLocator.healthConnectNutritionBridge.permissions +
        ServiceLocator.healthConnectWeightBridge.writePermissions +
        ServiceLocator.healthConnectWeightBridge.readPermissions

private fun backgroundHealthConnectReadPermissions(): Set<String> =
    ServiceLocator.healthConnectBridge.backgroundReadPermissions

/**
 * Permission set requested by the Settings master Health Connect toggle.
 * Background read is included so users can grant it opportunistically, but
 * [reconcileHealthConnect] treats it as optional and only uses it to schedule
 * background read workers.
 */
internal fun coreHealthConnectPermissions(): Set<String> =
    requiredHealthConnectPermissions() + backgroundHealthConnectReadPermissions()

internal suspend fun reconcileHealthConnect(
    context: Context = ServiceLocator.context,
    repo: UserSettingsRepository = ServiceLocator.userSettings,
    grantedHint: Set<String>? = null,
    enabledOverride: Boolean? = null,
): Boolean {
    val enabled = enabledOverride ?: runCatching {
        repo.flow.first().enableHealthConnect
    }.getOrDefault(false)
    if (!enabled) {
        StepSyncScheduler.cancel(context)
        return false
    }

    val granted = resolvedHealthConnectPermissions(context, grantedHint)
    val coreGranted = granted.containsAll(requiredHealthConnectPermissions())
    if (!coreGranted) {
        StepSyncScheduler.cancel(context)
        runCatching { repo.setHealthConnectEnabled(false) }
        return false
    }

    scheduleHealthConnectWork(context, granted)
    return true
}

private suspend fun resolvedHealthConnectPermissions(
    context: Context,
    grantedHint: Set<String>?,
): Set<String> = currentHealthConnectPermissions(context) + grantedHint.orEmpty()

private suspend fun currentHealthConnectPermissions(context: Context): Set<String> = runCatching {
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
        return@runCatching emptySet()
    }
    HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
}.getOrDefault(emptySet())

private fun canRunBackgroundHealthConnectReads(granted: Set<String>): Boolean =
    Build.VERSION.SDK_INT < ANDROID_15_API ||
        granted.containsAll(backgroundHealthConnectReadPermissions())

private fun scheduleHealthConnectWork(context: Context, granted: Set<String>) {
    if (granted.containsAll(ServiceLocator.healthConnectBpBridge.permissions)) {
        BpSyncWorker.enqueue(context)
    }
    if (granted.containsAll(ServiceLocator.healthConnectGlucoseBridge.permissions)) {
        GlucoseSyncWorker.enqueue(context)
    }
    val weight = ServiceLocator.healthConnectWeightBridge
    // WeightSyncWorker mixes two independent jobs, so its enqueue gate is the
    // OR of the two: the write-retry half (push locally-created rows to HC)
    // runs whenever WRITE is granted and must NOT depend on background-read;
    // the smart-scale import half (read from HC) stays behind the optional
    // background-read grant on Android 15+. Worker-side gates split the same way.
    if (granted.containsAll(weight.writePermissions) ||
        (canRunBackgroundHealthConnectReads(granted) && granted.containsAll(weight.readPermissions))
    ) {
        WeightSyncWorker.enqueue(context)
    }
    if (granted.containsAll(ServiceLocator.healthConnectExerciseBridge.permissions)) {
        ExerciseSyncWorker.enqueue(context)
    }

    if (canRunBackgroundHealthConnectReads(granted) &&
        granted.containsAll(ServiceLocator.healthConnectExerciseBridge.readPermissions)
    ) {
        StepSyncScheduler.schedule(context)
    } else {
        StepSyncScheduler.cancel(context)
    }
}

class SettingsViewModel(
    private val repo: UserSettingsRepository = ServiceLocator.userSettings,
    private val entitlementManager: EntitlementManager = ServiceLocator.entitlementManager,
) : ViewModel() {

    val state: StateFlow<UserSettings> = repo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings()
    )

    /**
     * The RESOLVED real subscription tier (cache ∪ live Play), for the Settings
     * Premium card's status row. NOT the gate truth — gates use
     * [EntitlementManager.isPremium]; this is purely "what does the user's Play
     * account say". Surfaced as-is from the manager's StateFlow.
     */
    val entitlement: StateFlow<Entitlement> = entitlementManager.entitlement

    /**
     * DEBUG-only: write the simulate-entitlement override ("premium"/"free"/null)
     * so we can demo the paywall locally without published Play products. Reflected
     * back into [state] via the DataStore flow ([UserSettings.debugPremiumOverride])
     * for the radio selection. No-op meaning in release (the override is ignored
     * there, and this card isn't shown).
     */
    fun setDebugPremiumOverride(value: String?) {
        viewModelScope.launch { repo.setDebugPremiumOverride(value) }
    }

    private val _hcDenied = Channel<Unit>(capacity = Channel.BUFFERED)
    /** Emits when the user dismissed/denied the Health Connect permission prompt. UI shows a snackbar. */
    val hcPermissionDenied: Flow<Unit> = _hcDenied.receiveAsFlow()

    fun setGuideline(g: HypertensionGuideline) { viewModelScope.launch { repo.setGuideline(g) } }

    /**
     * App-wide weight display unit ("kg"/"lb"). Canonical storage stays kg; this
     * only changes how values render. Reflected back into [state] via the
     * DataStore flow ([UserSettings.weightUnit]) for the radio selection.
     */
    fun setWeightUnit(raw: String) { viewModelScope.launch { repo.setWeightUnit(raw) } }

    fun setAppThemeMode(v: AppThemeMode) { viewModelScope.launch { repo.setAppThemeMode(v) } }

    /**
     * Sets the app language via the framework per-app locale. Bypasses the
     * DataStore repo entirely — the system owns and persists this value, and it
     * must stay per-device (never backed up/synced). The system recreates the
     * Activity, so the Settings radio re-reads the new current language.
     */
    fun setAppLanguage(context: Context, lang: AppLanguage) = LocaleHelper.apply(context, lang)

    /**
     * Called from the composable's permission-result callback after the user
     * has interacted with the Health Connect permission sheet. The master
     * toggle asks for background read opportunistically, but reconciliation only
     * requires the core foreground/write set; background read is allowed to be
     * denied and only gates read-back workers.
     */
    fun onHealthConnectGrantResult(granted: Set<String>) {
        viewModelScope.launch {
            val ctx = ServiceLocator.context
            val grantedNow = resolvedHealthConnectPermissions(ctx, granted)
            if (!grantedNow.containsAll(requiredHealthConnectPermissions())) {
                StepSyncScheduler.cancel(ctx)
                _hcDenied.trySend(Unit)
                return@launch
            }
            repo.setHealthConnectEnabled(true)
            val reconciled = reconcileHealthConnect(
                context = ctx,
                repo = repo,
                grantedHint = grantedNow,
                enabledOverride = true,
            )
            if (reconciled) {
                ServiceLocator.achievementStore.launchRefresh()
            } else {
                _hcDenied.trySend(Unit)
            }
        }
    }

    fun disableHealthConnect() {
        viewModelScope.launch {
            repo.setHealthConnectEnabled(false)
            reconcileHealthConnect(
                context = ServiceLocator.context,
                repo = repo,
                enabledOverride = false,
            )
        }
    }

    fun setRecognitionBackend(b: RecognitionBackend) {
        viewModelScope.launch { repo.setRecognitionBackend(b) }
    }
    fun setSelectedModelId(id: String) {
        viewModelScope.launch { repo.setSelectedModelId(id) }
    }
    fun setGeminiApiKey(key: String) {
        viewModelScope.launch { repo.setGeminiApiKey(key) }
    }
    fun setGeminiModel(id: String) {
        viewModelScope.launch { repo.setGeminiModel(id) }
    }
    fun setMaxNumTokens(v: Int) {
        viewModelScope.launch { repo.setMaxNumTokens(v) }
    }
    fun setSystemPrompt(v: String) {
        viewModelScope.launch { repo.setSystemPrompt(v) }
    }
    fun setVisionBackendOverride(v: VisionBackendOverride) {
        viewModelScope.launch { repo.setVisionBackendOverride(v) }
    }
    fun setEnableSpeculativeDecoding(v: Boolean) {
        viewModelScope.launch { repo.setEnableSpeculativeDecoding(v) }
    }
    fun setAllowDownloadOverCellular(v: Boolean) {
        viewModelScope.launch { repo.setAllowDownloadOverCellular(v) }
    }
    fun setDailyStepGoal(value: Int) {
        viewModelScope.launch {
            repo.setDailyStepGoal(value)
            // Re-evaluate streak medals against the new threshold immediately.
            ServiceLocator.achievementStore.refresh()
        }
    }
    fun setNotifyOnMedalUnlock(value: Boolean) {
        viewModelScope.launch { repo.setNotifyOnMedalUnlock(value) }
    }
    fun setChatPersona(v: String) {
        viewModelScope.launch { repo.setChatPersona(v) }
    }
    fun setUserNickname(v: String) {
        viewModelScope.launch { repo.setUserNickname(v) }
    }
    fun setChatIncludeRecordsContext(v: Boolean) {
        viewModelScope.launch { repo.setChatIncludeRecordsContext(v) }
    }
    fun setEnableCoach(v: Boolean) {
        viewModelScope.launch {
            repo.setEnableCoach(v)
            // Sync the WorkManager schedule with the toggle immediately so the
            // user doesn't have to wait for the next app launch for daily
            // reminders / weekly reports to start (or stop).
            if (v) {
                CoachReminderScheduler.scheduleAll(ServiceLocator.context)
            } else {
                CoachReminderScheduler.cancelAll(ServiceLocator.context)
            }
        }
    }

    fun setReminderEnabled(v: Boolean) {
        viewModelScope.launch {
            repo.setReminderEnabled(v)
            rescheduleReminders()
        }
    }
    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            repo.setReminderTime(hour, minute)
            rescheduleReminders()
        }
    }
    fun setReminderDaysMask(mask: Int) {
        viewModelScope.launch {
            repo.setReminderDaysMask(mask)
            rescheduleReminders()
        }
    }

    /**
     * Re-align the daily worker after a reminder-pref edit. Re-reads prefs via
     * [CoachReminderScheduler.scheduleAll] so the next fire matches the new
     * time/mask. No-op when Coach is disabled (the worker is already cancelled).
     */
    private suspend fun rescheduleReminders() {
        if (repo.flow.first().enableCoach) {
            CoachReminderScheduler.scheduleAll(ServiceLocator.context)
        }
    }

    /**
     * Called when the user dismisses the Health Connect SLEEP permission sheet.
     *
     * We don't trust the launcher's callback payload — on Android 15 the HC
     * controller short-circuits the runtime-permissions path so the modern
     * RequestMultiplePermissions() contract returns an empty map even after a
     * successful grant. Re-query the bridge instead; that hits HC directly
     * and gives the truth regardless of which permission UI rendered.
     */
    fun onSleepGrantResult(granted: Set<String>) {
        viewModelScope.launch {
            val ctx = ServiceLocator.context
            val grantedNow = resolvedHealthConnectPermissions(ctx, granted)
            val ok = runCatching {
                ServiceLocator.healthConnectBridge.hasSleepReadPermission()
            }.getOrDefault(false)
            if (ok) {
                repo.setSleepTrackingEnabled(true)
                if (canRunBackgroundHealthConnectReads(grantedNow)) {
                    SleepBackfillWorker.enqueue(ctx)
                }
            }
        }
    }

    fun disableSleepTracking() {
        viewModelScope.launch { repo.setSleepTrackingEnabled(false) }
    }

    fun onDietGrantResult(granted: Set<String>) {
        viewModelScope.launch {
            val ctx = ServiceLocator.context
            val grantedNow = resolvedHealthConnectPermissions(ctx, granted)
            val ok = runCatching {
                ServiceLocator.healthConnectBridge.hasNutritionReadPermission()
            }.getOrDefault(false)
            if (ok) {
                repo.setDietTrackingEnabled(true)
                if (canRunBackgroundHealthConnectReads(grantedNow)) {
                    NutritionBackfillWorker.enqueue(ctx)
                }
            }
        }
    }

    fun disableDietTracking() {
        viewModelScope.launch { repo.setDietTrackingEnabled(false) }
    }

    /**
     * Reset the privacy-policy acceptance to 0 so the AppNavHost gate sends the
     * user back to the consent step on next nav recomposition. didOnboard
     * stays true so we don't lose the user's nickname / settings — the
     * onboarding screen pre-fills them.
     */
    fun reviewConsent() {
        viewModelScope.launch { repo.setAcceptedPolicyVersion(0) }
    }

    /** Drives the Settings UI while the app-lock toggle migrates the DB. */
    sealed interface AppLockStatus {
        data object Idle : AppLockStatus
        /** DB encrypt/decrypt in progress — UI shows a blocking spinner. */
        data object Working : AppLockStatus
        /** Device has no biometric/PIN/password enrolled — UI deep-links to settings. */
        data object NeedsDeviceCredential : AppLockStatus
        /** Migration failed; [restored] true means data was rolled back intact. */
        data class Failed(val reason: String, val restored: Boolean) : AppLockStatus
    }

    private val _appLockStatus = MutableStateFlow<AppLockStatus>(AppLockStatus.Idle)
    val appLockStatus: StateFlow<AppLockStatus> = _appLockStatus.asStateFlow()

    fun dismissAppLockStatus() { _appLockStatus.value = AppLockStatus.Idle }

    /**
     * Toggle app-lock + at-rest encryption. Orchestration order matters:
     *  - enable : verify device can auth → encrypt DB → re-encrypt sensitive
     *             settings → persist flag.
     *  - disable: decrypt DB (clears the key) → rewrite settings plaintext →
     *             clear flag.
     * The DB migration is self-rolling-back; on failure the flag is left
     * untouched so the UI stays consistent with the on-disk state.
     */
    fun setAppLock(enable: Boolean) {
        if (_appLockStatus.value == AppLockStatus.Working) return
        val ctx = ServiceLocator.context
        if (enable && !canDeviceAuthenticate(ctx)) {
            _appLockStatus.value = AppLockStatus.NeedsDeviceCredential
            return
        }
        _appLockStatus.value = AppLockStatus.Working
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val keyStore = ServiceLocator.dbKeyStore
                if (enable) DbCipherMigration.encrypt(ctx, keyStore)
                else DbCipherMigration.decrypt(ctx, keyStore)
            }
            when (outcome) {
                is DbCipherMigration.Outcome.Success -> {
                    repo.migrateSensitiveFields()
                    repo.setAppLockEnabled(enable)
                    _appLockStatus.value = AppLockStatus.Idle
                }
                is DbCipherMigration.Outcome.Failed -> {
                    _appLockStatus.value =
                        AppLockStatus.Failed(outcome.reason, outcome.restored)
                }
            }
        }
    }
}
