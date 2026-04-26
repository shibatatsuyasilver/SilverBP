package com.silverbp.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.VisionBackendOverride
import com.silverbp.android.settings.UserSettings
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    val state: StateFlow<UserSettings> = repo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings()
    )

    fun setGuideline(g: HypertensionGuideline) { viewModelScope.launch { repo.setGuideline(g) } }
    fun setHealthConnect(enabled: Boolean) { viewModelScope.launch { repo.setHealthConnectEnabled(enabled) } }
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
}
