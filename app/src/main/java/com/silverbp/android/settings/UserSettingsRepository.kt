package com.silverbp.android.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import com.silverbp.android.backup.auto.AutoBackupFrequency
import com.silverbp.android.coach.DayOfWeekMask
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.recognition.GeminiCloudRecognizer
import com.silverbp.android.security.DbKeyStore
import com.silverbp.android.security.KeystoreStringCipher
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.VisionBackendOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_settings")

class UserSettingsRepository(private val context: Context) {

    // Sensitive free-text fields (API key / prompts / nickname) are stored
    // encrypted once the user opts into at-rest encryption. The marker is the
    // same synchronous source of truth Room uses; values carry a sentinel so
    // the read path is tolerant during opt-in/opt-out (mixed states).
    private val dbKey: DbKeyStore by lazy { DbKeyStore.create(context) }

    private fun protect(v: String): String =
        if (v.isNotEmpty() && dbKey.isDbEncrypted()) KeystoreStringCipher.encrypt(v) else v

    private fun reveal(v: String): String =
        if (KeystoreStringCipher.isEncrypted(v)) KeystoreStringCipher.decrypt(v) else v

    private object Keys {
        val GUIDELINE = stringPreferencesKey("guideline")
        val HC = booleanPreferencesKey("enable_health_connect")
        val ONBOARD = booleanPreferencesKey("did_onboard")
        val MODEL_DOWNLOADED = booleanPreferencesKey("model_downloaded")
        val BACKEND = stringPreferencesKey("recognition_backend")
        val MODEL_ID = stringPreferencesKey("selected_model_id")
        val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val MAX_NUM_TOKENS = intPreferencesKey("max_num_tokens")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val VISION_OVERRIDE = stringPreferencesKey("vision_backend_override")
        val SPECULATIVE_DECODING = booleanPreferencesKey("enable_speculative_decoding")
        val DAILY_STEP_GOAL = intPreferencesKey("daily_step_goal")
        val NOTIFY_MEDAL_UNLOCK = booleanPreferencesKey("notify_medal_unlock")
        val DID_OFFER_NOTIF_PROMPT = booleanPreferencesKey("did_offer_notif_prompt")
        val CHAT_PERSONA = stringPreferencesKey("chat_persona")
        val CHAT_INCLUDE_RECORDS = booleanPreferencesKey("chat_include_records")
        val ENABLE_COACH = booleanPreferencesKey("enable_coach")
        val REMINDER_ENABLED = booleanPreferencesKey("coach_reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("coach_reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("coach_reminder_minute")
        val REMINDER_DAYS_MASK = intPreferencesKey("coach_reminder_days_mask")
        val WEEKLY_AEROBIC_MIN = intPreferencesKey("weekly_aerobic_min")
        val DAILY_SODIUM_MG = intPreferencesKey("daily_sodium_mg")
        val TARGET_SLEEP_HOURS = floatPreferencesKey("target_sleep_hours")
        val SLEEP_TRACKING = booleanPreferencesKey("sleep_tracking_enabled")
        val DIET_TRACKING = booleanPreferencesKey("diet_tracking_enabled")
        val USER_NICKNAME = stringPreferencesKey("user_nickname")
        val ACCEPTED_POLICY_VERSION = intPreferencesKey("accepted_policy_version")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_TIMEOUT = intPreferencesKey("app_lock_timeout_seconds")

        // Auto-backup (Google Drive appDataFolder). FREQ is the user-facing
        // cadence; ACCOUNT_* identify the linked Google account; LAST_BACKUP_*
        // back the status row in BackupScreen.
        val AUTO_BACKUP_FREQ = stringPreferencesKey("auto_backup_frequency")
        val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
        val GOOGLE_ACCOUNT_ID = stringPreferencesKey("google_account_id")
        val LAST_BACKUP_AT_MS = longPreferencesKey("last_backup_at_ms")
        val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        val LAST_BACKUP_ERROR_AT_MS = longPreferencesKey("last_backup_error_at_ms")

        // Goal profile (onboarding Phase 4) — feeds CoachEngine plan generation.
        val PRIMARY_GOAL = stringPreferencesKey("primary_goal")
        val EXPERIENCE_LEVEL = stringPreferencesKey("experience_level")
        val WEEKLY_AVAILABILITY_DAYS = intPreferencesKey("weekly_availability_days")
        val TRAINING_STYLE = stringPreferencesKey("training_style")
    }

    val flow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            guideline = prefs[Keys.GUIDELINE]
                ?.let { runCatching { HypertensionGuideline.fromRaw(it) }.getOrNull() }
                ?: HypertensionGuideline.Taiwan2022,
            enableHealthConnect = prefs[Keys.HC] ?: false,
            didOnboard = prefs[Keys.ONBOARD] ?: false,
            modelDownloaded = prefs[Keys.MODEL_DOWNLOADED] ?: false,
            recognitionBackend = prefs[Keys.BACKEND]
                ?.let { RecognitionBackend.fromRaw(it) }
                ?: RecognitionBackend.Local,
            selectedModelId = prefs[Keys.MODEL_ID] ?: ModelCatalog.default.id,
            geminiApiKey = reveal(prefs[Keys.GEMINI_KEY] ?: ""),
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: GeminiCloudRecognizer.DEFAULT_MODEL,
            maxNumTokens = prefs[Keys.MAX_NUM_TOKENS] ?: 2048,
            systemPrompt = reveal(prefs[Keys.SYSTEM_PROMPT] ?: ""),
            visionBackendOverride = prefs[Keys.VISION_OVERRIDE]
                ?.let { VisionBackendOverride.fromRaw(it) }
                ?: VisionBackendOverride.Auto,
            enableSpeculativeDecoding = prefs[Keys.SPECULATIVE_DECODING] ?: true,
            dailyStepGoal = prefs[Keys.DAILY_STEP_GOAL] ?: 8000,
            notifyOnMedalUnlock = prefs[Keys.NOTIFY_MEDAL_UNLOCK] ?: true,
            didOfferNotificationPrompt = prefs[Keys.DID_OFFER_NOTIF_PROMPT] ?: false,
            chatPersona = reveal(prefs[Keys.CHAT_PERSONA] ?: ""),
            chatIncludeRecordsContext = prefs[Keys.CHAT_INCLUDE_RECORDS] ?: true,
            enableCoach = prefs[Keys.ENABLE_COACH] ?: true,
            reminderEnabled = prefs[Keys.REMINDER_ENABLED] ?: true,
            reminderHour = prefs[Keys.REMINDER_HOUR] ?: 7,
            reminderMinute = prefs[Keys.REMINDER_MINUTE] ?: 0,
            reminderDaysMask = prefs[Keys.REMINDER_DAYS_MASK] ?: DayOfWeekMask.ALL,
            weeklyAerobicMinTarget = prefs[Keys.WEEKLY_AEROBIC_MIN] ?: 150,
            dailySodiumTargetMg = prefs[Keys.DAILY_SODIUM_MG] ?: 2000,
            targetSleepHours = prefs[Keys.TARGET_SLEEP_HOURS] ?: 7.0f,
            sleepTrackingEnabled = prefs[Keys.SLEEP_TRACKING] ?: false,
            dietTrackingEnabled = prefs[Keys.DIET_TRACKING] ?: false,
            userNickname = reveal(prefs[Keys.USER_NICKNAME] ?: ""),
            acceptedPolicyVersion = prefs[Keys.ACCEPTED_POLICY_VERSION] ?: 0,
            appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
            appLockTimeoutSeconds = prefs[Keys.APP_LOCK_TIMEOUT] ?: 60,
            autoBackupFrequency = AutoBackupFrequency.fromRaw(prefs[Keys.AUTO_BACKUP_FREQ]),
            googleAccountEmail = prefs[Keys.GOOGLE_ACCOUNT_EMAIL] ?: "",
            googleAccountId = prefs[Keys.GOOGLE_ACCOUNT_ID] ?: "",
            lastBackupAtMs = prefs[Keys.LAST_BACKUP_AT_MS] ?: 0L,
            lastBackupError = prefs[Keys.LAST_BACKUP_ERROR] ?: "",
            lastBackupErrorAtMs = prefs[Keys.LAST_BACKUP_ERROR_AT_MS] ?: 0L,
            primaryGoal = prefs[Keys.PRIMARY_GOAL] ?: "",
            experienceLevel = prefs[Keys.EXPERIENCE_LEVEL] ?: "",
            weeklyAvailabilityDays = prefs[Keys.WEEKLY_AVAILABILITY_DAYS] ?: 0,
            trainingStyle = prefs[Keys.TRAINING_STYLE] ?: "",
        )
    }

    suspend fun setGuideline(g: HypertensionGuideline) {
        context.dataStore.edit { it[Keys.GUIDELINE] = g.raw }
    }
    suspend fun setHealthConnectEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HC] = enabled }
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
        context.dataStore.edit { it[Keys.GEMINI_KEY] = protect(key) }
    }
    suspend fun setGeminiModel(id: String) {
        context.dataStore.edit { it[Keys.GEMINI_MODEL] = id }
    }
    suspend fun setMaxNumTokens(v: Int) {
        context.dataStore.edit { it[Keys.MAX_NUM_TOKENS] = v }
    }
    suspend fun setSystemPrompt(v: String) {
        context.dataStore.edit { it[Keys.SYSTEM_PROMPT] = protect(v) }
    }
    suspend fun setVisionBackendOverride(v: VisionBackendOverride) {
        context.dataStore.edit { it[Keys.VISION_OVERRIDE] = v.raw }
    }
    suspend fun setEnableSpeculativeDecoding(v: Boolean) {
        context.dataStore.edit { it[Keys.SPECULATIVE_DECODING] = v }
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
        context.dataStore.edit { it[Keys.CHAT_PERSONA] = protect(v) }
    }
    suspend fun setChatIncludeRecordsContext(v: Boolean) {
        context.dataStore.edit { it[Keys.CHAT_INCLUDE_RECORDS] = v }
    }
    suspend fun setEnableCoach(v: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_COACH] = v }
    }
    suspend fun setReminderEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.REMINDER_ENABLED] = v }
    }
    suspend fun setReminderTime(hour: Int, minute: Int) {
        context.dataStore.edit {
            it[Keys.REMINDER_HOUR] = hour.coerceIn(0, 23)
            it[Keys.REMINDER_MINUTE] = minute.coerceIn(0, 59)
        }
    }
    suspend fun setReminderDaysMask(mask: Int) {
        context.dataStore.edit { it[Keys.REMINDER_DAYS_MASK] = mask and DayOfWeekMask.ALL }
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
    suspend fun setUserNickname(v: String) {
        val sanitized = v.trim().take(MAX_NICKNAME_LEN)
        context.dataStore.edit { it[Keys.USER_NICKNAME] = protect(sanitized) }
    }
    suspend fun setAcceptedPolicyVersion(v: Int) {
        context.dataStore.edit { it[Keys.ACCEPTED_POLICY_VERSION] = v }
    }

    suspend fun setPrimaryGoal(v: PrimaryGoal) {
        context.dataStore.edit { it[Keys.PRIMARY_GOAL] = v.raw }
    }
    suspend fun setExperienceLevel(v: ExperienceLevel) {
        context.dataStore.edit { it[Keys.EXPERIENCE_LEVEL] = v.raw }
    }
    suspend fun setWeeklyAvailabilityDays(v: Int) {
        context.dataStore.edit { it[Keys.WEEKLY_AVAILABILITY_DAYS] = v.coerceIn(0, 7) }
    }
    suspend fun setTrainingStyle(v: TrainingStyle) {
        context.dataStore.edit { it[Keys.TRAINING_STYLE] = v.raw }
    }

    suspend fun setAppLockEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK_ENABLED] = v }
    }
    suspend fun setAppLockTimeoutSeconds(v: Int) {
        context.dataStore.edit { it[Keys.APP_LOCK_TIMEOUT] = v.coerceIn(0, 600) }
    }

    // ============================================================
    // Auto-backup (Google Drive) setters
    // ============================================================

    suspend fun setAutoBackupFrequency(v: AutoBackupFrequency) {
        context.dataStore.edit { it[Keys.AUTO_BACKUP_FREQ] = v.raw }
    }
    suspend fun setGoogleAccount(email: String, id: String) {
        context.dataStore.edit {
            it[Keys.GOOGLE_ACCOUNT_EMAIL] = email
            it[Keys.GOOGLE_ACCOUNT_ID] = id
        }
    }
    suspend fun clearGoogleAccount() {
        context.dataStore.edit {
            it.remove(Keys.GOOGLE_ACCOUNT_EMAIL)
            it.remove(Keys.GOOGLE_ACCOUNT_ID)
        }
    }
    /** Worker writes both timestamp + clears prior error on success. */
    suspend fun recordBackupSuccess(atMs: Long) {
        context.dataStore.edit {
            it[Keys.LAST_BACKUP_AT_MS] = atMs
            it[Keys.LAST_BACKUP_ERROR] = ""
            it[Keys.LAST_BACKUP_ERROR_AT_MS] = 0L
        }
    }
    /** Worker writes error text + timestamp on failure; leaves last-success untouched. */
    suspend fun recordBackupFailure(message: String, atMs: Long) {
        context.dataStore.edit {
            it[Keys.LAST_BACKUP_ERROR] = message
            it[Keys.LAST_BACKUP_ERROR_AT_MS] = atMs
        }
    }

    /**
     * Rewrite the sensitive free-text fields so their on-disk form matches the
     * current [DbKeyStore.isDbEncrypted] marker. Called by the app-lock
     * opt-in/opt-out flow **after** the marker has been flipped:
     *  - opt-in  (marker now true)  → plaintext values get re-stored encrypted
     *  - opt-out (marker now false) → encrypted values get re-stored plaintext
     * Idempotent: [reveal] tolerates either state, [protect] keys off the marker.
     */
    suspend fun migrateSensitiveFields() {
        val sensitive = listOf(Keys.GEMINI_KEY, Keys.SYSTEM_PROMPT, Keys.CHAT_PERSONA, Keys.USER_NICKNAME)
        context.dataStore.edit { prefs ->
            for (k in sensitive) {
                val current = prefs[k] ?: continue
                prefs[k] = protect(reveal(current))
            }
        }
    }

    // ============================================================
    // Backup / SETTINGS_KV sync helpers
    // ============================================================
    //
    // snapshotKv() / applyKvSingle() / removeKv() 由 SettingsKvSyncMapper 呼叫,
    // 把 DataStore 變成可被 backup snapshot 攜帶的 type-erased KV map.
    //
    // 敏感欄位(Gemini API key, system prompt, chat persona, nickname) 在 snapshot
    // 時 reveal 為明文(AES-GCM 容器保護整體機密性);在 applyKvSingle 時走 protect
    // 重新包裝為目標裝置的 Keystore 加密格式.

    /**
     * 抓 DataStore 目前的非預設值,以鍵名 → [KvValue] 的形式回傳.
     * 預設值的鍵不會出現在輸出中(備份體積、跨版本 schema 兼容性).
     */
    suspend fun snapshotKv(): Map<String, KvValue> {
        val prefs = context.dataStore.data.first()
        val out = LinkedHashMap<String, KvValue>()

        // strings
        prefs[Keys.GUIDELINE]?.let { out[Keys.GUIDELINE.name] = KvValue.T(it) }
        prefs[Keys.BACKEND]?.let { out[Keys.BACKEND.name] = KvValue.T(it) }
        prefs[Keys.MODEL_ID]?.let { out[Keys.MODEL_ID.name] = KvValue.T(it) }
        prefs[Keys.GEMINI_MODEL]?.let { out[Keys.GEMINI_MODEL.name] = KvValue.T(it) }
        prefs[Keys.VISION_OVERRIDE]?.let { out[Keys.VISION_OVERRIDE.name] = KvValue.T(it) }
        prefs[Keys.PRIMARY_GOAL]?.let { out[Keys.PRIMARY_GOAL.name] = KvValue.T(it) }
        prefs[Keys.EXPERIENCE_LEVEL]?.let { out[Keys.EXPERIENCE_LEVEL.name] = KvValue.T(it) }
        prefs[Keys.TRAINING_STYLE]?.let { out[Keys.TRAINING_STYLE.name] = KvValue.T(it) }
        // Auto-backup frequency is the only auto-backup key that's portable
        // across devices — restoring on a new phone should remember the user's
        // preferred cadence even though the Google account link must be redone.
        prefs[Keys.AUTO_BACKUP_FREQ]?.let { out[Keys.AUTO_BACKUP_FREQ.name] = KvValue.T(it) }

        // sensitive strings — reveal to plaintext for backup; AES-GCM container
        // protects the snapshot file as a whole.
        prefs[Keys.GEMINI_KEY]?.let {
            val plain = reveal(it)
            if (plain.isNotEmpty()) out[Keys.GEMINI_KEY.name] = KvValue.T(plain)
        }
        prefs[Keys.SYSTEM_PROMPT]?.let {
            val plain = reveal(it)
            if (plain.isNotEmpty()) out[Keys.SYSTEM_PROMPT.name] = KvValue.T(plain)
        }
        prefs[Keys.CHAT_PERSONA]?.let {
            val plain = reveal(it)
            if (plain.isNotEmpty()) out[Keys.CHAT_PERSONA.name] = KvValue.T(plain)
        }
        prefs[Keys.USER_NICKNAME]?.let {
            val plain = reveal(it)
            if (plain.isNotEmpty()) out[Keys.USER_NICKNAME.name] = KvValue.T(plain)
        }

        // booleans
        prefs[Keys.HC]?.let { out[Keys.HC.name] = KvValue.B(it) }
        prefs[Keys.ONBOARD]?.let { out[Keys.ONBOARD.name] = KvValue.B(it) }
        prefs[Keys.MODEL_DOWNLOADED]?.let { out[Keys.MODEL_DOWNLOADED.name] = KvValue.B(it) }
        prefs[Keys.SPECULATIVE_DECODING]?.let { out[Keys.SPECULATIVE_DECODING.name] = KvValue.B(it) }
        prefs[Keys.NOTIFY_MEDAL_UNLOCK]?.let { out[Keys.NOTIFY_MEDAL_UNLOCK.name] = KvValue.B(it) }
        prefs[Keys.DID_OFFER_NOTIF_PROMPT]?.let { out[Keys.DID_OFFER_NOTIF_PROMPT.name] = KvValue.B(it) }
        prefs[Keys.CHAT_INCLUDE_RECORDS]?.let { out[Keys.CHAT_INCLUDE_RECORDS.name] = KvValue.B(it) }
        prefs[Keys.ENABLE_COACH]?.let { out[Keys.ENABLE_COACH.name] = KvValue.B(it) }
        prefs[Keys.REMINDER_ENABLED]?.let { out[Keys.REMINDER_ENABLED.name] = KvValue.B(it) }
        prefs[Keys.SLEEP_TRACKING]?.let { out[Keys.SLEEP_TRACKING.name] = KvValue.B(it) }
        prefs[Keys.DIET_TRACKING]?.let { out[Keys.DIET_TRACKING.name] = KvValue.B(it) }
        prefs[Keys.APP_LOCK_ENABLED]?.let { out[Keys.APP_LOCK_ENABLED.name] = KvValue.B(it) }

        // ints
        prefs[Keys.MAX_NUM_TOKENS]?.let { out[Keys.MAX_NUM_TOKENS.name] = KvValue.I(it) }
        prefs[Keys.DAILY_STEP_GOAL]?.let { out[Keys.DAILY_STEP_GOAL.name] = KvValue.I(it) }
        prefs[Keys.REMINDER_HOUR]?.let { out[Keys.REMINDER_HOUR.name] = KvValue.I(it) }
        prefs[Keys.REMINDER_MINUTE]?.let { out[Keys.REMINDER_MINUTE.name] = KvValue.I(it) }
        prefs[Keys.REMINDER_DAYS_MASK]?.let { out[Keys.REMINDER_DAYS_MASK.name] = KvValue.I(it) }
        prefs[Keys.WEEKLY_AEROBIC_MIN]?.let { out[Keys.WEEKLY_AEROBIC_MIN.name] = KvValue.I(it) }
        prefs[Keys.DAILY_SODIUM_MG]?.let { out[Keys.DAILY_SODIUM_MG.name] = KvValue.I(it) }
        prefs[Keys.ACCEPTED_POLICY_VERSION]?.let { out[Keys.ACCEPTED_POLICY_VERSION.name] = KvValue.I(it) }
        prefs[Keys.APP_LOCK_TIMEOUT]?.let { out[Keys.APP_LOCK_TIMEOUT.name] = KvValue.I(it) }
        prefs[Keys.WEEKLY_AVAILABILITY_DAYS]?.let { out[Keys.WEEKLY_AVAILABILITY_DAYS.name] = KvValue.I(it) }

        // floats
        prefs[Keys.TARGET_SLEEP_HOURS]?.let { out[Keys.TARGET_SLEEP_HOURS.name] = KvValue.F(it) }

        return out
    }

    /**
     * 寫入單一鍵的值(來自備份匯入). 鍵名用 [Keys] 內的 name 字串對應.
     * 敏感字串會走 [protect] 重新加密;其他直接寫.
     * 未知鍵安靜忽略(跨版本 forward-compat).
     */
    suspend fun applyKvSingle(keyName: String, value: KvValue) {
        context.dataStore.edit { prefs ->
            when (keyName) {
                // strings
                Keys.GUIDELINE.name -> if (value is KvValue.T) prefs[Keys.GUIDELINE] = value.value
                Keys.BACKEND.name -> if (value is KvValue.T) prefs[Keys.BACKEND] = value.value
                Keys.MODEL_ID.name -> if (value is KvValue.T) prefs[Keys.MODEL_ID] = value.value
                Keys.GEMINI_MODEL.name -> if (value is KvValue.T) prefs[Keys.GEMINI_MODEL] = value.value
                Keys.VISION_OVERRIDE.name -> if (value is KvValue.T) prefs[Keys.VISION_OVERRIDE] = value.value
                Keys.AUTO_BACKUP_FREQ.name -> if (value is KvValue.T) prefs[Keys.AUTO_BACKUP_FREQ] = value.value
                Keys.PRIMARY_GOAL.name -> if (value is KvValue.T) prefs[Keys.PRIMARY_GOAL] = value.value
                Keys.EXPERIENCE_LEVEL.name -> if (value is KvValue.T) prefs[Keys.EXPERIENCE_LEVEL] = value.value
                Keys.TRAINING_STYLE.name -> if (value is KvValue.T) prefs[Keys.TRAINING_STYLE] = value.value

                // sensitive strings — re-protect with this device's Keystore alias.
                Keys.GEMINI_KEY.name -> if (value is KvValue.T) prefs[Keys.GEMINI_KEY] = protect(value.value)
                Keys.SYSTEM_PROMPT.name -> if (value is KvValue.T) prefs[Keys.SYSTEM_PROMPT] = protect(value.value)
                Keys.CHAT_PERSONA.name -> if (value is KvValue.T) prefs[Keys.CHAT_PERSONA] = protect(value.value)
                Keys.USER_NICKNAME.name -> if (value is KvValue.T) {
                    prefs[Keys.USER_NICKNAME] = protect(value.value.trim().take(MAX_NICKNAME_LEN))
                }

                // booleans
                Keys.HC.name -> if (value is KvValue.B) prefs[Keys.HC] = value.value
                Keys.ONBOARD.name -> if (value is KvValue.B) prefs[Keys.ONBOARD] = value.value
                Keys.MODEL_DOWNLOADED.name -> if (value is KvValue.B) prefs[Keys.MODEL_DOWNLOADED] = value.value
                Keys.SPECULATIVE_DECODING.name -> if (value is KvValue.B) prefs[Keys.SPECULATIVE_DECODING] = value.value
                Keys.NOTIFY_MEDAL_UNLOCK.name -> if (value is KvValue.B) prefs[Keys.NOTIFY_MEDAL_UNLOCK] = value.value
                Keys.DID_OFFER_NOTIF_PROMPT.name -> if (value is KvValue.B) prefs[Keys.DID_OFFER_NOTIF_PROMPT] = value.value
                Keys.CHAT_INCLUDE_RECORDS.name -> if (value is KvValue.B) prefs[Keys.CHAT_INCLUDE_RECORDS] = value.value
                Keys.ENABLE_COACH.name -> if (value is KvValue.B) prefs[Keys.ENABLE_COACH] = value.value
                Keys.REMINDER_ENABLED.name -> if (value is KvValue.B) prefs[Keys.REMINDER_ENABLED] = value.value
                Keys.SLEEP_TRACKING.name -> if (value is KvValue.B) prefs[Keys.SLEEP_TRACKING] = value.value
                Keys.DIET_TRACKING.name -> if (value is KvValue.B) prefs[Keys.DIET_TRACKING] = value.value
                Keys.APP_LOCK_ENABLED.name -> if (value is KvValue.B) prefs[Keys.APP_LOCK_ENABLED] = value.value

                // ints
                Keys.MAX_NUM_TOKENS.name -> if (value is KvValue.I) prefs[Keys.MAX_NUM_TOKENS] = value.value
                Keys.DAILY_STEP_GOAL.name -> if (value is KvValue.I) prefs[Keys.DAILY_STEP_GOAL] = value.value.coerceIn(2_000, 30_000)
                Keys.REMINDER_HOUR.name -> if (value is KvValue.I) prefs[Keys.REMINDER_HOUR] = value.value.coerceIn(0, 23)
                Keys.REMINDER_MINUTE.name -> if (value is KvValue.I) prefs[Keys.REMINDER_MINUTE] = value.value.coerceIn(0, 59)
                Keys.REMINDER_DAYS_MASK.name -> if (value is KvValue.I) prefs[Keys.REMINDER_DAYS_MASK] = value.value and DayOfWeekMask.ALL
                Keys.WEEKLY_AEROBIC_MIN.name -> if (value is KvValue.I) prefs[Keys.WEEKLY_AEROBIC_MIN] = value.value.coerceIn(60, 600)
                Keys.DAILY_SODIUM_MG.name -> if (value is KvValue.I) prefs[Keys.DAILY_SODIUM_MG] = value.value.coerceIn(1000, 4000)
                Keys.ACCEPTED_POLICY_VERSION.name -> if (value is KvValue.I) prefs[Keys.ACCEPTED_POLICY_VERSION] = value.value
                Keys.APP_LOCK_TIMEOUT.name -> if (value is KvValue.I) prefs[Keys.APP_LOCK_TIMEOUT] = value.value.coerceIn(0, 600)
                Keys.WEEKLY_AVAILABILITY_DAYS.name -> if (value is KvValue.I) prefs[Keys.WEEKLY_AVAILABILITY_DAYS] = value.value.coerceIn(0, 7)

                // floats
                Keys.TARGET_SLEEP_HOURS.name -> if (value is KvValue.F) prefs[Keys.TARGET_SLEEP_HOURS] = value.value.coerceIn(4f, 12f)

                // 未知鍵: 跨版本 forward-compat,安靜忽略.
            }
        }
    }

    /** 刪除單一鍵 — 等於把該欄位重設為預設值(因為 Settings flow 內所有讀取都有 `?: default`). */
    suspend fun removeKv(keyName: String) {
        context.dataStore.edit { prefs ->
            val key = prefs.asMap().keys.firstOrNull { it.name == keyName } ?: return@edit
            prefs.remove(key)
        }
    }

    companion object {
        const val MAX_NICKNAME_LEN: Int = 20
    }
}
