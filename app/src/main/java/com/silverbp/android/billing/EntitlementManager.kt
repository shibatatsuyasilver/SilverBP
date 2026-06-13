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
 *  2. **Live Play purchases** — two inputs:
 *       a) [queryPurchasesOnStartup] / [refresh] do a *pull* query of the
 *          [BillingGateway]; a CONFIRMED PURCHASED sub writes Premium back to the
 *          cache, a CONFIRMED empty result writes Free. A `null` ("could not
 *          query" — Play unavailable / non-OK) leaves the cache untouched so a
 *          transient hiccup never downgrades a paying subscriber (finding #1).
 *       b) [BillingGateway.entitlementUpdates] is the *push* stream off the
 *          PurchasesUpdatedListener; a freshly-completed purchase promotes us to
 *          Premium immediately (so the paywall auto-dismisses — findings #2/#3).
 *     PENDING purchases grant nothing (see [activeEntitlementFromPurchases]).
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
        // Wire the live-purchase PUSH input (findings #2/#3): the
        // PurchasesUpdatedListener emits the freshly-completed purchase set, which
        // the gateway maps to an Entitlement. A Premium emission means the user
        // JUST bought a sub — promote immediately + persist so the paywall's
        // LaunchedEffect(entitlement) auto-dismisses and gates unlock without a
        // restart / manual restore. We DON'T downgrade to Free off this stream: a
        // real downgrade is confirmed only by a successful pull [refresh]; a
        // USER_CANCELED / error never reaches entitlementUpdates as Premium, and a
        // stray Free here must not clobber a cached Premium.
        scope.launch {
            gateway.entitlementUpdates.collect { live ->
                if (live == Entitlement.Premium) {
                    _entitlement.value = Entitlement.Premium
                    runCatching { settings.setLastKnownEntitlement(Entitlement.Premium.name) }
                }
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
     *
     * CRITICAL (finding #1): only a CONFIRMED query result overwrites the cache.
     * [BillingGateway.queryActiveEntitlement] returns `null` when Play could not
     * be queried (unavailable / non-OK / connection exhausted) — indistinguishable
     * from a thrown error for cache-safety purposes — and we leave the last-known
     * tier untouched in that case. We persist a downgrade to Free ONLY on a real
     * OK query that found zero active PURCHASED subs. This prevents a transient
     * Play hiccup (or the 24 h worker firing on "network up, Play unreachable")
     * from silently downgrading a paying subscriber and poisoning the cache.
     */
    suspend fun refresh() {
        val resolved = runCatching { gateway.queryActiveEntitlement() }
            .getOrNull() // thrown error → null, same as "could not query"
            ?: return // gateway unavailable / non-OK → keep cached value
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
