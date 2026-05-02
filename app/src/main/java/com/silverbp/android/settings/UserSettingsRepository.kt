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
        val DAILY_STEP_GOAL = intPreferencesKey("daily_step_goal")
        val NOTIFY_MEDAL_UNLOCK = booleanPreferencesKey("notify_medal_unlock")
        val DID_OFFER_NOTIF_PROMPT = booleanPreferencesKey("did_offer_notif_prompt")
        val CHAT_PERSONA = stringPreferencesKey("chat_persona")
        val CHAT_INCLUDE_RECORDS = booleanPreferencesKey("chat_include_records")
        val ENABLE_COACH = booleanPreferencesKey("enable_coach")
        val WEEKLY_AEROBIC_MIN = intPreferencesKey("weekly_aerobic_min")
        val DAILY_SODIUM_MG = intPreferencesKey("daily_sodium_mg")
        val TARGET_SLEEP_HOURS = floatPreferencesKey("target_sleep_hours")
        val SLEEP_TRACKING = booleanPreferencesKey("sleep_tracking_enabled")
        val DIET_TRACKING = booleanPreferencesKey("diet_tracking_enabled")
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
            dailyStepGoal = prefs[Keys.DAILY_STEP_GOAL] ?: 8000,
            notifyOnMedalUnlock = prefs[Keys.NOTIFY_MEDAL_UNLOCK] ?: true,
            didOfferNotificationPrompt = prefs[Keys.DID_OFFER_NOTIF_PROMPT] ?: false,
            chatPersona = prefs[Keys.CHAT_PERSONA] ?: "",
            chatIncludeRecordsContext = prefs[Keys.CHAT_INCLUDE_RECORDS] ?: true,
            enableCoach = prefs[Keys.ENABLE_COACH] ?: true,
            weeklyAerobicMinTarget = prefs[Keys.WEEKLY_AEROBIC_MIN] ?: 150,
            dailySodiumTargetMg = prefs[Keys.DAILY_SODIUM_MG] ?: 2000,
            targetSleepHours = prefs[Keys.TARGET_SLEEP_HOURS] ?: 7.0f,
            sleepTrackingEnabled = prefs[Keys.SLEEP_TRACKING] ?: false,
            dietTrackingEnabled = prefs[Keys.DIET_TRACKING] ?: false,
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
    suspend fun setDailyStepGoal(v: Int) {
        context.dataStore.edit { it[Keys.DAILY_STEP_GOAL] = v.coerceIn(2_000, 30_000) }
    }
    suspend fun setNotifyOnMedalUnlock(v: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_MEDAL_UNLOCK] = v }
    }
    suspend fun setDidOfferNotificationPrompt(v: Boolean) {
        context.dataStore.edit { it[Keys.DID_OFFER_NOTIF_PROMPT] = v }
    }
    suspend fun setChatPersona(v: String) {
        context.dataStore.edit { it[Keys.CHAT_PERSONA] = v }
    }
    suspend fun setChatIncludeRecordsContext(v: Boolean) {
        context.dataStore.edit { it[Keys.CHAT_INCLUDE_RECORDS] = v }
    }
    suspend fun setEnableCoach(v: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_COACH] = v }
    }
    suspend fun setWeeklyAerobicMinTarget(v: Int) {
        context.dataStore.edit { it[Keys.WEEKLY_AEROBIC_MIN] = v.coerceIn(60, 600) }
    }
    suspend fun setDailySodiumTargetMg(v: Int) {
        context.dataStore.edit { it[Keys.DAILY_SODIUM_MG] = v.coerceIn(1000, 4000) }
    }
    suspend fun setTargetSleepHours(v: Float) {
        context.dataStore.edit { it[Keys.TARGET_SLEEP_HOURS] = v.coerceIn(4f, 12f) }
    }
    suspend fun setSleepTrackingEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.SLEEP_TRACKING] = v }
    }
    suspend fun setDietTrackingEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.DIET_TRACKING] = v }
    }
}
