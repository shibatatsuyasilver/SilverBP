package com.silverbp.android.billing

import kotlinx.coroutines.flow.Flow

/**
 * The narrow slice of settings [EntitlementManager] depends on — the device-local
 * last-known-tier cache + the DEBUG paywall override. Implemented by
 * [com.silverbp.android.settings.UserSettingsRepository] in production; a plain
 * in-memory fake in unit tests so the manager's cache read/write and override
 * paths can be exercised on the JVM without a DataStore / Context.
 */
interface EntitlementStore {
    /** Emits the cached tier name ("Free"/"Premium") and the debug override on every change. */
    val entitlementSnapshots: Flow<EntitlementSnapshot>

    /** Persist the resolved tier so the next cold start emits it immediately. */
    suspend fun setLastKnownEntitlement(raw: String)
}

/**
 * The two device-local billing fields surfaced to [EntitlementManager], decoupled
 * from the full UserSettings so the manager doesn't pull in unrelated settings.
 */
data class EntitlementSnapshot(
    val lastKnownEntitlement: String,
    val debugPremiumOverride: String?,
)
