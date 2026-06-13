package com.silverbp.android.settings

import com.silverbp.android.backup.auto.AutoBackupFrequency
import com.silverbp.android.coach.DayOfWeekMask
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.recognition.GeminiCloudRecognizer
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.VisionBackendOverride

data class UserSettings(
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    /** App color theme. Defaults to Dark — the app is dark-first. */
    val appThemeMode: AppThemeMode = AppThemeMode.Dark,
    val enableHealthConnect: Boolean = false,
    val didOnboard: Boolean = false,
    /**
     * True once the user chose 「稍後再說」 on the first-launch Google sign-in gate
     * (or unlinked from Backup). Lets [com.silverbp.android.ui.nav.AppNavHost]
     * release HOME without a linked account — auto-backup simply stays off until
     * the user links one from the Backup screen.
     */
    val skippedGoogleLink: Boolean = false,
    val modelDownloaded: Boolean = false,
    /**
     * True once the user has been through the first-launch AI backend picker
     * ([com.silverbp.android.ui.onboarding.OnboardingModelScreen]). Gates that
     * one-time screen in [com.silverbp.android.ui.nav.AppNavHost]; the escape
     * hatch ("type readings myself") also sets it so the picker never re-fires.
     */
    val pickedAiBackend: Boolean = false,

    /** "local" (default) or "cloud" — picks which [com.silverbp.android.recognition.BpRecognizer] to use. */
    val recognitionBackend: RecognitionBackend = RecognitionBackend.Local,
    /** Local model id (matches one of [ModelCatalog.variants]). */
    val selectedModelId: String = ModelCatalog.default.id,
    /** Google AI Studio API key for the cloud route. Stored in plain DataStore — fine for personal use,
     *  swap to EncryptedSharedPreferences if you want device-level secrecy. */
    val geminiApiKey: String = "",
    /** Gemini model id (e.g. "gemini-2.5-flash"). */
    val geminiModel: String = GeminiCloudRecognizer.DEFAULT_MODEL,

    /** LiteRT-LM KV-cache budget. Discrete options exposed in Settings: 1024 / 1536 / 2048 / 3072 / 4096. */
    val maxNumTokens: Int = 2048,
    /** User-edited OCR system prompt; blank → fall back to [com.silverbp.android.recognition.BpPrompt.defaultSystem]. */
    val systemPrompt: String = "",

    /**
     * Advanced override for which backend the LiteRT-LM vision encoder runs on.
     * [VisionBackendOverride.Auto] (default) defers to
     * [com.silverbp.android.recognition.DeviceCapabilities.recommendedVisionBackend]
     * and retries CPU if GPU init fails. Explicit values skip auto-detect.
     */
    val visionBackendOverride: VisionBackendOverride = VisionBackendOverride.Auto,

    /**
     * Enables LiteRT-LM v0.11.0 Multi-Token Prediction (MTP) speculative
     * decoding for variants whose .litertlm file ships MTP heads
     * ([com.silverbp.android.recognition.ModelVariant.supportsSpeculativeDecoding]).
     * Default ON (~2x decode speedup on mobile GPUs); flip OFF here for
     * compatibility debugging. Read at engine init only — toggling requires
     * `ModelBootstrap.reloadCurrentVariant`.
     */
    val enableSpeculativeDecoding: Boolean = true,

    /** Daily step target used as the streak medal threshold. Tunable in Settings. */
    val dailyStepGoal: Int = 8000,
    /** When true, surface a system notification on medal unlock (perm-gated). */
    val notifyOnMedalUnlock: Boolean = true,
    /**
     * Internal flag — true once the in-app banner has prompted the user about
     * notification permission. Prevents re-asking after a denial.
     */
    val didOfferNotificationPrompt: Boolean = false,

    /**
     * User-edited chat persona. Blank → fall back to
     * [com.silverbp.android.ui.chat.CHAT_SYSTEM_PERSONA]. Applies on the next
     * sent turn — no model reload needed.
     */
    val chatPersona: String = "",
    /**
     * When true (default) the chat system prompt includes a markdown summary of
     * the user's BP / exercise / medal records (see RecordsContextBuilder).
     * Disable for "stateless" generic Q&A — useful when the records are noisy
     * or when testing model behavior on neutral prompts.
     */
    val chatIncludeRecordsContext: Boolean = true,

    /**
     * Master toggle for the lifestyle Coach feature. When false the Coach tab
     * is hidden from the bottom navigation. Other Coach plumbing (workers,
     * anomaly watcher) inspects this flag before posting notifications.
     */
    val enableCoach: Boolean = true,

    /**
     * Daily coach reminder. Defaults reproduce the legacy hardcoded behaviour
     * (every day at 07:00) so existing users see no change.
     *  - [reminderEnabled]: master switch for the daily task notification.
     *  - [reminderHour] / [reminderMinute]: wall-clock firing time.
     *  - [reminderDaysMask]: ISO-day mask (see [DayOfWeekMask]) of weekdays to
     *    fire on. The worker still runs daily but no-ops on excluded days.
     */
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 7,
    val reminderMinute: Int = 0,
    val reminderDaysMask: Int = DayOfWeekMask.ALL,

    /** Weekly aerobic-minute target (WHO 2020 default). Used by [com.silverbp.android.coach.CoachEngine]. */
    val weeklyAerobicMinTarget: Int = 150,
    /** Daily sodium ceiling in mg (AHA default 2000). */
    val dailySodiumTargetMg: Int = 2000,
    /** Daily sleep target in hours (NSF: 7–8 h). */
    val targetSleepHours: Float = 7.0f,

    /**
     * Preferred blood-glucose display/entry unit (v19). Raw value of
     * [com.silverbp.android.core.GlucoseUnit] — "mgdl" (default, Taiwan glucose
     * meters' mainstream) or "mmol". Storage is always canonical mg/dL; this
     * drives how values are shown and the default unit when entering a reading.
     */
    val glucoseUnit: String = "mgdl",

    /** Opt-in: read sleep duration from Health Connect. Off by default — user must grant the perm. */
    val sleepTrackingEnabled: Boolean = false,
    /** Opt-in: read nutrition (sodium) from Health Connect. */
    val dietTrackingEnabled: Boolean = false,

    /**
     * How the coach should address the user (e.g. 阿公 / 王先生 / 小明 / 你).
     * Blank = no nickname; the coach will not address the user by name.
     * Captured in onboarding ([com.silverbp.android.ui.onboarding.OnboardingNicknameScreen])
     * and editable later in Settings. Injected into all coach LLM prompts.
     */
    val userNickname: String = "",

    /**
     * Privacy policy version the user last accepted. Compared against
     * [com.silverbp.android.legal.CURRENT_PRIVACY_POLICY_VERSION] in the
     * onboarding gate ([com.silverbp.android.ui.nav.AppNavHost]) — when the
     * stored value is lower (or 0 for a fresh install) the user is sent back
     * to the consent step. Bump the constant when policy text changes.
     */
    val acceptedPolicyVersion: Int = 0,

    /**
     * Opt-in app-lock + at-rest encryption. When true the Room DB is
     * SQLCipher-encrypted, sensitive settings are encrypted, and the UI
     * requires biometric / device-credential unlock after the app has been
     * backgrounded longer than [appLockTimeoutSeconds]. Default off — enabling
     * runs an irreversible-feeling (but reversible) DB migration. The actual
     * "is the DB encrypted" source of truth is the Keystore-backed
     * [com.silverbp.android.security.DbKeyStore] marker; this flag drives the
     * UI gate + Settings row and is kept in lock-step with it.
     */
    val appLockEnabled: Boolean = false,
    /** Grace period before a foregrounded app re-locks. 60 s default (elderly-friendly). */
    val appLockTimeoutSeconds: Int = 60,

    /**
     * Auto-backup cadence to Google Drive (appDataFolder).
     * [AutoBackupFrequency.Off] means the user has not opted into auto-backup
     * (or has turned it off again); the manual export/import flow remains
     * available regardless.
     */
    val autoBackupFrequency: AutoBackupFrequency = AutoBackupFrequency.Off,
    /** Linked Google account email; blank when none. UI shows this in the account row. */
    val googleAccountEmail: String = "",
    /**
     * Stable Google account identifier (sub claim). Persisted alongside the
     * email because emails can change; the scheduler authorizes with this id
     * to make sure we keep targeting the same Drive even after a rename.
     */
    val googleAccountId: String = "",
    /** Epoch ms of the last successful upload, 0 when no backup has succeeded yet. */
    val lastBackupAtMs: Long = 0L,
    /** Last failure message; blank when last attempt succeeded (or none yet). */
    val lastBackupError: String = "",
    /** Epoch ms of the last failure, 0 when no failure has been recorded. */
    val lastBackupErrorAtMs: Long = 0L,

    /**
     * Goal profile captured during onboarding (Phase 4) and read by
     * [com.silverbp.android.coach.CoachEngine] to shape the weekly plan.
     * Enum-backed fields store the enum raw ([com.silverbp.android.settings.PrimaryGoal] etc.);
     * "" / 0 mean unset, so pre-existing users keep the engine defaults.
     */
    val primaryGoal: String = "",
    val experienceLevel: String = "",
    /** Preferred exercise days/week; 0 = unset → engine falls back to its default cadence. */
    val weeklyAvailabilityDays: Int = 0,
    val trainingStyle: String = "",

    /**
     * Last-known subscription tier, cached so [com.silverbp.android.billing.EntitlementManager]
     * can emit *immediately* on cold start (no flicker, no offline lock-out)
     * before the live Play query lands. Stored as the [com.silverbp.android.billing.Entitlement]
     * name string ("Free"/"Premium"); "Free" is the safe default. Device-local —
     * deliberately NOT carried in settings sync (each device resolves its own
     * Play account), mirroring [com.silverbp.android.core.member.CurrentMemberStore].
     */
    val lastKnownEntitlement: String = "Free",

    /**
     * DEBUG-only paywall override. "premium" / "free" force [EntitlementManager.isPremium]
     * regardless of the real Play entitlement; null = follow real. Lets us demo
     * the paywall locally without published Play products even while
     * PREMIUM_ENFORCED is false. Device-local; never synced; ignored in release
     * builds. See the DEBUG card in SettingsScreen.
     */
    val debugPremiumOverride: String? = null,
)
