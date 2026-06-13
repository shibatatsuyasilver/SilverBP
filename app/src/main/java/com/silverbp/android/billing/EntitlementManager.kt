package com.silverbp.android.billing

import com.silverbp.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single source of truth for "is this user Premium?". Resolution combines three
 * inputs, in this priority:
 *
 *  1. **DataStore last-known cache** — emitted *immediately* on cold start so the
 *     UI never flickers and an offline user who already paid isn't locked out.
 *  2. **Live Play purchases** — [queryPurchasesOnStartup] / [refresh] query the
 *     [BillingGateway]; a PURCHASED sub writes Premium back to the cache, an
 *     empty result writes Free. PENDING purchases grant nothing
 *     (see [activeEntitlementFromPurchases]).
 *  3. **DEBUG override** — only consulted by [isPremium] (not [entitlement]); lets
 *     us demo the paywall locally without published products.
 *
 * [entitlement] is the *resolved real* tier (cache ∪ live), surfaced to Settings
 * so the user sees their actual subscription status. Gates must NOT read it
 * directly — they call [isPremium], which layers the beta-enforcement flag and
 * the debug override on top.
 *
 * ## isPremium() truth table
 *
 * | PREMIUM_ENFORCED | debug override | resolved entitlement | isPremium() |
 * |------------------|----------------|----------------------|-------------|
 * | false (beta)     | null           | Free                 | **true**    | ← beta: everything unlocked
 * | false (beta)     | null           | Premium              | **true**    |
 * | false (beta)     | "free"         | (any)                | **false**   | ← demo the paywall
 * | false (beta)     | "premium"      | (any)                | **true**    |
 * | true (live)      | null           | Free                 | **false**   |
 * | true (live)      | null           | Premium              | **true**    |
 * | true (live)      | "free"         | (any)                | **false**   | ← debug only; release strips it
 * | true (live)      | "premium"      | (any)                | **true**    |
 *
 * Rule order: a debug override (DEBUG builds only) ALWAYS wins → else if not
 * enforced, true → else resolved == Premium. With the flag false and no override
 * there is ZERO behaviour change vs. today: every gate sees Premium.
 */
class EntitlementManager(
    private val gateway: BillingGateway,
    private val settings: EntitlementStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _entitlement = MutableStateFlow(Entitlement.Free)

    /**
     * The RESOLVED real entitlement (cache ∪ live Play), independent of the beta
     * gate and debug override. For UI display of actual sub status (Settings).
     * Gates use [isPremium] instead.
     */
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    /** In-memory mirror of the DEBUG override so [isPremium] stays synchronous. */
    @Volatile private var debugOverride: Entitlement? = null

    init {
        // Emit the cached tier immediately (anti-flicker / offline) and keep both
        // the resolved state and the debug-override mirror in lock-step with the
        // DataStore for the process lifetime.
        scope.launch {
            // Seed the resolved state from the cache once up front (covers the
            // window before the collector below has its first emission), then
            // track every later cache write for the process lifetime.
            val seed = runCatching { settings.entitlementSnapshots.first() }.getOrNull()
            _entitlement.value = Entitlement.fromRaw(seed?.lastKnownEntitlement)
            if (BuildConfig.DEBUG) debugOverride = parseOverride(seed?.debugPremiumOverride)
            settings.entitlementSnapshots
                .distinctUntilChanged()
                .collect { snap ->
                    _entitlement.value = Entitlement.fromRaw(snap.lastKnownEntitlement)
                    // The DEBUG override only matters in debug builds; release
                    // never writes it, but guard anyway so isPremium() can't be
                    // forced from a stale value in a release artifact.
                    if (BuildConfig.DEBUG) debugOverride = parseOverride(snap.debugPremiumOverride)
                }
        }
    }

    /**
     * The one method gates call. Delegates to the pure [resolveIsPremium] with
     * the current real inputs; see the class-level truth table.
     */
    fun isPremium(): Boolean = resolveIsPremium(
        enforced = BuildConfig.PREMIUM_ENFORCED,
        override = debugOverride,
        resolved = _entitlement.value,
    )

    /**
     * Cold-start resolution: read live Play purchases, write the result back to
     * the cache (which feeds [entitlement] via the DataStore collector). Safe on
     * the emulator — an unavailable gateway returns empty → resolves to Free
     * (still unlocked while not enforced). Never throws.
     */
    suspend fun queryPurchasesOnStartup() = refresh()

    /**
     * Re-query live Play purchases and persist the resolved tier. Called on
     * startup, by the 24 h revalidation worker, and after a purchase / "restore
     * purchases" tap. Failures are swallowed → the last-known cache stands.
     */
    suspend fun refresh() {
        val resolved = runCatching { gateway.queryActiveEntitlement() }
            .getOrElse { return } // gateway unavailable → keep cached value
        _entitlement.value = resolved
        runCatching { settings.setLastKnownEntitlement(resolved.name) }
    }

    private fun parseOverride(raw: String?): Entitlement? = when (raw?.lowercase()) {
        "premium" -> Entitlement.Premium
        "free" -> Entitlement.Free
        else -> null
    }

    companion object {
        /**
         * Pure entitlement decision — the whole truth table in one expression,
         * extracted so it's exhaustively unit-testable across BOTH values of
         * [enforced] (the real [BuildConfig.PREMIUM_ENFORCED] is a compile-time
         * constant, so tests can only flip it here, not at runtime).
         *
         * Order is load-bearing: a debug [override] always wins, then the beta
         * gate, then the real [resolved] tier.
         */
        fun resolveIsPremium(
            enforced: Boolean,
            override: Entitlement?,
            resolved: Entitlement,
        ): Boolean {
            if (override != null) return override == Entitlement.Premium
            if (!enforced) return true
            return resolved == Entitlement.Premium
        }
    }
}
