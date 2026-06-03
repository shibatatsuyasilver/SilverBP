package com.silverbp.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.achievements.StepSyncScheduler
import com.silverbp.android.coach.CoachReminderScheduler
import com.silverbp.android.coach.NutritionBackfillWorker
import com.silverbp.android.coach.SleepBackfillWorker
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.VisionBackendOverride
import com.silverbp.android.security.DbCipherMigration
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

class SettingsViewModel(
    private val repo: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    val state: StateFlow<UserSettings> = repo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings()
    )

    private val _hcDenied = Channel<Unit>(capacity = Channel.BUFFERED)
    /** Emits when the user dismissed/denied the Health Connect READ_STEPS prompt. UI shows a snackbar. */
    val hcPermissionDenied: Flow<Unit> = _hcDenied.receiveAsFlow()

    fun setGuideline(g: HypertensionGuideline) { viewModelScope.launch { repo.setGuideline(g) } }

    /**
     * Called from the composable's permission-result callback after the user
     * has interacted with the Health Connect permission sheet. We only flip
     * `enableHealthConnect` to true when every required read permission was
     * granted; otherwise the toggle stays off and we emit a denied event so
     * the UI can prompt the user to open Health Connect manually.
     */
    fun onHealthConnectGrantResult(granted: Set<String>) {
        viewModelScope.launch {
            val required = ServiceLocator.healthConnectExerciseBridge.readPermissions
            if (granted.containsAll(required)) {
                repo.setHealthConnectEnabled(true)
                StepSyncScheduler.schedule(ServiceLocator.context)
                ServiceLocator.achievementStore.launchRefresh()
            } else {
                _hcDenied.trySend(Unit)
            }
        }
    }

    fun disableHealthConnect() {
        viewModelScope.launch {
            repo.setHealthConnectEnabled(false)
            StepSyncScheduler.cancel(ServiceLocator.context)
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
    fun onSleepGrantResult(@Suppress("UNUSED_PARAMETER") granted: Set<String>) {
        viewModelScope.launch {
            val ok = runCatching {
                ServiceLocator.healthConnectBridge.hasSleepReadPermission()
            }.getOrDefault(false)
            if (ok) {
                repo.setSleepTrackingEnabled(true)
                SleepBackfillWorker.enqueue(ServiceLocator.context)
            }
        }
    }

    fun disableSleepTracking() {
        viewModelScope.launch { repo.setSleepTrackingEnabled(false) }
    }

    fun onDietGrantResult(@Suppress("UNUSED_PARAMETER") granted: Set<String>) {
        viewModelScope.launch {
            val ok = runCatching {
                ServiceLocator.healthConnectBridge.hasNutritionReadPermission()
            }.getOrDefault(false)
            if (ok) {
                repo.setDietTrackingEnabled(true)
                NutritionBackfillWorker.enqueue(ServiceLocator.context)
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
