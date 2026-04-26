package com.silverbp.android.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.recognition.GeminiCloudRecognizer
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.VisionBackendOverride
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
        val MAX_NUM_TOKENS = intPreferencesKey("max_num_tokens")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val VISION_OVERRIDE = stringPreferencesKey("vision_backend_override")
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
            maxNumTokens = prefs[Keys.MAX_NUM_TOKENS] ?: 2048,
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: "",
            visionBackendOverride = prefs[Keys.VISION_OVERRIDE]
                ?.let { VisionBackendOverride.fromRaw(it) }
                ?: VisionBackendOverride.Auto,
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
    suspend fun setMaxNumTokens(v: Int) {
        context.dataStore.edit { it[Keys.MAX_NUM_TOKENS] = v }
    }
    suspend fun setSystemPrompt(v: String) {
        context.dataStore.edit { it[Keys.SYSTEM_PROMPT] = v }
    }
    suspend fun setVisionBackendOverride(v: VisionBackendOverride) {
        context.dataStore.edit { it[Keys.VISION_OVERRIDE] = v.raw }
    }
}
