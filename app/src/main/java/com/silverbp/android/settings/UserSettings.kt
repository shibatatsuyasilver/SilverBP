package com.silverbp.android.settings

import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.recognition.GeminiCloudRecognizer
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.VisionBackendOverride

data class UserSettings(
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    val enableHealthConnect: Boolean = false,
    val enableCloudSync: Boolean = false,        // stub — feature deferred (matches iOS)
    val didOnboard: Boolean = false,
    val modelDownloaded: Boolean = false,

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

    /** Weekly aerobic-minute target (WHO 2020 default). Used by [com.silverbp.android.coach.CoachEngine]. */
    val weeklyAerobicMinTarget: Int = 150,
    /** Daily sodium ceiling in mg (AHA default 2000). */
    val dailySodiumTargetMg: Int = 2000,
    /** Daily sleep target in hours (NSF: 7–8 h). */
    val targetSleepHours: Float = 7.0f,

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
)
