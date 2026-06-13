package com.silverbp.android.billing

/**
 * The resolved subscription tier. Deliberately a tiny closed set — gates only
 * ever ask "is this Premium?" via [EntitlementManager.isPremium]; this enum is
 * the *displayable* truth (Settings shows the real status regardless of the
 * [com.silverbp.android.BuildConfig.PREMIUM_ENFORCED] beta gate).
 *
 * Persisted as the [name] string in the DataStore last-known cache; [fromRaw]
 * tolerates unknown / legacy values by falling back to [Free] (the safe default
 * — an unparseable cache must never hand out Premium).
 */
enum class Entitlement {
    Free,
    Premium;

    companion object {
        fun fromRaw(raw: String?): Entitlement =
            entries.firstOrNull { it.name == raw } ?: Free
    }
}
