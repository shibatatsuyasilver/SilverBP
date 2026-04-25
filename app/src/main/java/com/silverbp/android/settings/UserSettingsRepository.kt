package com.silverbp.android.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.recognition.GeminiCloudRecognizer
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.RecognitionBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_settings")

class UserSettingsRepository(private val context: Context) {

    private object Keys {
        val GUIDELINE = stringPreferencesKey("guideline")
        val HC = booleanPreferencesKey("enable_health_connect")
        val CLOUD = booleanPreferencesKey("enable_cloud_sync")
        val ONBOARD = booleanPreferencesKey("did_onboard")
        val MODEL_DOWNLOADED = booleanPreferencesKey("model_downloaded")
        val BACKEND = stringPreferencesKey("recognition_backend")
        val MODEL_ID = stringPreferencesKey("selected_model_id")
        val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
    }

    val flow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            guideline = prefs[Keys.GUIDELINE]
                ?.let { runCatching { HypertensionGuideline.fromRaw(it) }.getOrNull() }
                ?: HypertensionGuideline.Taiwan2022,
            enableHealthConnect = prefs[Keys.HC] ?: false,
            enableCloudSync = prefs[Keys.CLOUD] ?: false,
            didOnboard = prefs[Keys.ONBOARD] ?: false,
            modelDownloaded = prefs[Keys.MODEL_DOWNLOADED] ?: false,
            recognitionBackend = prefs[Keys.BACKEND]
                ?.let { RecognitionBackend.fromRaw(it) }
                ?: RecognitionBackend.Local,
            selectedModelId = prefs[Keys.MODEL_ID] ?: ModelCatalog.default.id,
            geminiApiKey = prefs[Keys.GEMINI_KEY] ?: "",
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: GeminiCloudRecognizer.DEFAULT_MODEL,
        )
    }

    suspend fun setGuideline(g: HypertensionGuideline) {
        context.dataStore.edit { it[Keys.GUIDELINE] = g.raw }
    }
    suspend fun setHealthConnectEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HC] = enabled }
    }
    suspend fun setCloudSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CLOUD] = enabled }
    }
    suspend fun setDidOnboard(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARD] = value }
    }
    suspend fun setModelDownloaded(value: Boolean) {
        context.dataStore.edit { it[Keys.MODEL_DOWNLOADED] = value }
    }
    suspend fun setRecognitionBackend(b: RecognitionBackend) {
        context.dataStore.edit { it[Keys.BACKEND] = b.raw }
    }
    suspend fun setSelectedModelId(id: String) {
        context.dataStore.edit { it[Keys.MODEL_ID] = id }
    }
    suspend fun setGeminiApiKey(key: String) {
        context.dataStore.edit { it[Keys.GEMINI_KEY] = key }
    }
    suspend fun setGeminiModel(id: String) {
        context.dataStore.edit { it[Keys.GEMINI_MODEL] = id }
    }
}
