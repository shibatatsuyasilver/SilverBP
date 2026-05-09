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
import com.silverbp.android.settings.UserSettings
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun setCloudSync(enabled: Boolean) { viewModelScope.launch { repo.setCloudSyncEnabled(enabled) } }

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
}
