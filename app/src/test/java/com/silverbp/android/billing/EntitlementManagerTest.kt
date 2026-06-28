package com.silverbp.android.billing

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [EntitlementManager]'s decision logic, cache read/write, and the
 * PENDING-grants-nothing rule. The compile-time [com.silverbp.android.BuildConfig.PREMIUM_ENFORCED]
 * is false in the debug test variant, so the *full* truth table (including the
 * enforced branch) is exercised through the pure [EntitlementManager.resolveIsPremium];
 * the manager's runtime behaviour is checked against fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementManagerTest {

    // -------- isPremium() truth table (pure, both PREMIUM_ENFORCED values) -----

    @Test fun `not enforced + no override resolves Premium regardless of real tier`() {
        // Beta: everything unlocked, no behaviour change vs. today.
        assertTrue(EntitlementManager.resolveIsPremium(enforced = false, override = null, resolved = Entitlement.Free))
        assertTrue(EntitlementManager.resolveIsPremium(enforced = false, override = null, resolved = Entitlement.Premium))
    }

    @Test fun `not enforced + override Free demos the paywall`() {
        assertFalse(EntitlementManager.resolveIsPremium(enforced = false, override = Entitlement.Free, resolved = Entitlement.Premium))
        assertFalse(EntitlementManager.resolveIsPremium(enforced = false, override = Entitlement.Free, resolved = Entitlement.Free))
    }

    @Test fun `not enforced + override Premium unlocks`() {
        assertTrue(EntitlementManager.resolveIsPremium(enforced = false, override = Entitlement.Premium, resolved = Entitlement.Free))
    }

    @Test fun `enforced + no override follows the resolved tier`() {
        assertFalse(EntitlementManager.resolveIsPremium(enforced = true, override = null, resolved = Entitlement.Free))
        assertTrue(EntitlementManager.resolveIsPremium(enforced = true, override = null, resolved = Entitlement.Premium))
    }

    @Test fun `enforced + override always wins over the resolved tier`() {
        assertFalse(EntitlementManager.resolveIsPremium(enforced = true, override = Entitlement.Free, resolved = Entitlement.Premium))
        assertTrue(EntitlementManager.resolveIsPremium(enforced = true, override = Entitlement.Premium, resolved = Entitlement.Free))
    }

    // -------- PENDING / PURCHASED rule ----------------------------------------

    @Test fun `PURCHASED grants Premium, PENDING and UNSPECIFIED grant nothing`() {
        assertEquals(Entitlement.Premium, entitlementFromStates(listOf(Purchase.PurchaseState.PURCHASED)))
        assertEquals(Entitlement.Free, entitlementFromStates(listOf(Purchase.PurchaseState.PENDING)))
        assertEquals(Entitlement.Free, entitlementFromStates(listOf(Purchase.PurchaseState.UNSPECIFIED_STATE)))
        assertEquals(Entitlement.Free, entitlementFromStates(emptyList()))
        // A PURCHASED among PENDINGs still grants Premium.
        assertEquals(
            Entitlement.Premium,
            entitlementFromStates(listOf(Purchase.PurchaseState.PENDING, Purchase.PurchaseState.PURCHASED)),
        )
    }

    // -------- cache read / write + refresh ------------------------------------

    @Test fun `cold start emits the cached tier immediately`() = runTest {
        val store = FakeStore(cached = "Premium")
        val mgr = EntitlementManager(FakeGateway(), store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals(Entitlement.Premium, mgr.entitlement.value)
    }

    @Test fun `refresh with a PURCHASED sub resolves Premium and writes the cache`() = runTest {
        // PENDING is in the set too — the pure rule (entitlementFromStates) maps
        // PURCHASED→Premium, so the live entitlement the gateway returns is Premium.
        val store = FakeStore(cached = "Free")
        val gateway = FakeGateway(
            live = entitlementFromStates(listOf(Purchase.PurchaseState.PENDING, Purchase.PurchaseState.PURCHASED)),
        )
        val mgr = EntitlementManager(gateway, store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        mgr.refresh()
        advanceUntilIdle()
        assertEquals(Entitlement.Premium, mgr.entitlement.value)
        assertEquals("Premium", store.written)
    }

    @Test fun `refresh with a PENDING-only sub stays Free and caches Free`() = runTest {
        val store = FakeStore(cached = "Free")
        val gateway = FakeGateway(live = entitlementFromStates(listOf(Purchase.PurchaseState.PENDING)))
        val mgr = EntitlementManager(gateway, store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        mgr.refresh()
        advanceUntilIdle()
        assertEquals(Entitlement.Free, mgr.entitlement.value)
        assertEquals("Free", store.written)
    }

    @Test fun `refresh keeps the cached tier when the gateway throws`() = runTest {
        // Emulator path: gateway throws → cache stands, nothing written.
        val store = FakeStore(cached = "Premium")
        val gateway = FakeGateway(throwOnQuery = true)
        val mgr = EntitlementManager(gateway, store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        mgr.refresh()
        advanceUntilIdle()
        assertEquals(Entitlement.Premium, mgr.entitlement.value)
        assertEquals(null, store.written)
    }

    @Test fun `refresh keeps the cached Premium when Play could not be queried (null)`() = runTest {
        // Finding #1: a transient Play hiccup (connection exhausted / non-OK
        // response) surfaces as queryActiveEntitlement()==null, NOT Free. The
        // cache must NOT be poisoned with Free — a paying subscriber stays Premium.
        val store = FakeStore(cached = "Premium")
        val gateway = FakeGateway(live = null) // null = "could not query"
        val mgr = EntitlementManager(gateway, store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        mgr.refresh()
        advanceUntilIdle()
        assertEquals(Entitlement.Premium, mgr.entitlement.value)
        assertEquals(null, store.written) // nothing persisted on an unavailable query
    }

    @Test fun `refresh downgrades to Free only on a confirmed empty query`() = runTest {
        // The legitimate cancel/expiry path: an OK query that genuinely found no
        // active subs (Free, NOT null) DOES persist the downgrade.
        val store = FakeStore(cached = "Premium")
        val gateway = FakeGateway(live = Entitlement.Free)
        val mgr = EntitlementManager(gateway, store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        mgr.refresh()
        advanceUntilIdle()
        assertEquals(Entitlement.Free, mgr.entitlement.value)
        assertEquals("Free", store.written)
    }

    // -------- live-purchase PUSH input (findings #2 / #3) ----------------------

    @Test fun `a live PURCHASED emission promotes to Premium and caches it`() = runTest {
        // Findings #2/#3: the PurchasesUpdatedListener fires after a successful
        // purchase → gateway.entitlementUpdates emits Premium → the manager flips
        // _entitlement to Premium WITHOUT a restart/restore, so PaywallSheet's
        // LaunchedEffect(entitlement) auto-dismisses.
        val store = FakeStore(cached = "Free")
        val gateway = FakeGateway()
        val mgr = EntitlementManager(gateway, store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals(Entitlement.Free, mgr.entitlement.value)
        gateway.emitPurchase(Purchase.PurchaseState.PURCHASED)
        advanceUntilIdle()
        assertEquals(Entitlement.Premium, mgr.entitlement.value)
        assertEquals("Premium", store.written)
    }

    @Test fun `a live PENDING-only emission does not promote and does not clobber the cache`() = runTest {
        // PENDING grants nothing; the push stream must not flip us to Premium, and
        // a non-Premium emission must NOT downgrade a cached Premium (downgrades
        // are confirmed only by a successful pull refresh).
        val store = FakeStore(cached = "Premium")
        val gateway = FakeGateway()
        val mgr = EntitlementManager(gateway, store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        gateway.emitPurchase(Purchase.PurchaseState.PENDING)
        advanceUntilIdle()
        assertEquals(Entitlement.Premium, mgr.entitlement.value) // cache untouched
        assertEquals(null, store.written)
    }

    // -------- product filter + refresh contract + pending (#23/#24/#25) --------

    @Test fun `a sub for a different product never grants Premium (forPremiumProduct filter, #23)`() {
        // The Play SUBS query returns EVERY active sub on the account; an unrelated
        // product (a different SKU / a shared Play account) must never grant OUR
        // Premium. forPremiumProduct() is the single choke point that drops it.
        val foreign = purchase("some_other_subscription", Purchase.PurchaseState.PURCHASED)
        assertTrue(listOf(foreign).forPremiumProduct().isEmpty())
        assertEquals(Entitlement.Free, activeEntitlementFromPurchases(listOf(foreign)))
        // Control: our product PURCHASED still grants Premium even bundled with the
        // foreign sub, proving the filter keeps ours and only drops the unrelated one.
        val ours = purchase(PREMIUM_PRODUCT_ID, Purchase.PurchaseState.PURCHASED)
        assertEquals(Entitlement.Premium, activeEntitlementFromPurchases(listOf(foreign, ours)))
    }

    @Test fun `refresh with only a foreign-product sub confirms a query yet resolves Free (#23)`() = runTest {
        // End-to-end through the gateway's DEFAULT queryActiveEntitlement(): a
        // PURCHASED sub for the wrong product is a CONFIRMED query (refresh==true)
        // but maps to Free, so the manager does not promote to Premium.
        val store = FakeStore(cached = "Free")
        val gateway = FakePurchaseGateway(listOf(purchase("some_other_subscription", Purchase.PurchaseState.PURCHASED)))
        val mgr = EntitlementManager(gateway, store, scope = TestScope(StandardTestDispatcher(testScheduler)))
        val queried = mgr.refresh()
        advanceUntilIdle()
        assertTrue(queried) // Play WAS reached (a real, confirmed result)
        assertEquals(Entitlement.Free, mgr.entitlement.value)
    }

    @Test fun `refresh returns false when Play is unavailable or throws, true on a confirmed query (#24)`() = runTest {
        // Finding #24: "restore purchases" gates its success/empty message on this
        // boolean so a STALE cache can never masquerade as a fresh restore. A false
        // means "couldn't check" (→ Unavailable in PaywallViewModel), NOT "no sub".
        fun mgr(g: BillingGateway) = EntitlementManager(
            g, FakeStore(cached = "Premium"), scope = TestScope(StandardTestDispatcher(testScheduler)),
        )
        assertFalse(mgr(FakeGateway(live = null)).refresh())        // could-not-query (null)
        assertFalse(mgr(FakeGateway(throwOnQuery = true)).refresh()) // thrown error → null path
        assertTrue(mgr(FakeGateway(live = Entitlement.Free)).refresh())    // confirmed empty
        assertTrue(mgr(FakeGateway(live = Entitlement.Premium)).refresh()) // confirmed premium
    }

    @Test fun `queryHasPendingPremiumPurchase surfaces a PENDING premium sub distinctly (#25)`() = runTest {
        // Finding #25: a PENDING (slow card / cash / family-approval) purchase grants
        // nothing yet, but is surfaced as "payment pending" rather than the
        // misleading "no subscription found".
        val pendingOurs = FakePurchaseGateway(listOf(purchase(PREMIUM_PRODUCT_ID, Purchase.PurchaseState.PENDING)))
        assertTrue(pendingOurs.queryHasPendingPremiumPurchase())
        assertEquals(Entitlement.Free, pendingOurs.queryActiveEntitlement()) // pending != active Premium

        // A PURCHASED sub is active, not pending.
        val purchasedOurs = FakePurchaseGateway(listOf(purchase(PREMIUM_PRODUCT_ID, Purchase.PurchaseState.PURCHASED)))
        assertFalse(purchasedOurs.queryHasPendingPremiumPurchase())
        assertEquals(Entitlement.Premium, purchasedOurs.queryActiveEntitlement())

        // A PENDING sub for a DIFFERENT product is filtered out — no spurious note.
        val pendingForeign = FakePurchaseGateway(listOf(purchase("some_other_subscription", Purchase.PurchaseState.PENDING)))
        assertFalse(pendingForeign.queryHasPendingPremiumPurchase())

        // "Could not query" (null) yields false, never a spurious pending note.
        assertFalse(FakePurchaseGateway(null).queryHasPendingPremiumPurchase())
    }

    // ---------------------------------------------------------------- fakes ----

    /**
     * Build a real [Purchase] from raw Play JSON so the [forPremiumProduct] /
     * [activeEntitlementFromPurchases] choke point (and the default gateway
     * methods) run for real on the JVM (org.json is the genuine impl in unit
     * tests). [getProducts] reads "productId"; [getPurchaseState] returns PENDING
     * only when the raw "purchaseState" int is 4, else PURCHASED — so we translate
     * the requested [Purchase.PurchaseState] constant into that raw encoding.
     */
    private fun purchase(productId: String, state: Int): Purchase {
        val raw = JSONObject()
            .put("productId", productId)
            .put("purchaseState", if (state == Purchase.PurchaseState.PENDING) 4 else 0)
            .toString()
        return Purchase(raw, "signature")
    }


    /** In-memory [EntitlementStore]. [stateBased] resolution via the gateway is
     *  tested separately; here we just record cache writes + replay one snapshot. */
    private class FakeStore(cached: String, override: String? = null) : EntitlementStore {
        var written: String? = null
        private val snapshot = MutableStateFlow(EntitlementSnapshot(cached, override))
        override val entitlementSnapshots = snapshot
        override suspend fun setLastKnownEntitlement(raw: String) {
            written = raw
            snapshot.value = snapshot.value.copy(lastKnownEntitlement = raw)
        }
    }

    /** Fake gateway. The manager consumes [queryActiveEntitlement] (resolved
     *  tier) + [entitlementUpdates] (push), so we never construct a real
     *  [Purchase] except via the state-int constructor exercised by Robolectric;
     *  here the push stream is driven through [emitPurchase] with bare state ints
     *  mapped via [entitlementFromStates] (no JSON-parsing ctor). */
    private class FakeGateway(
        /** Resolved live tier; `null` models "could not query" (unavailable / non-OK). */
        private val live: Entitlement? = Entitlement.Free,
        private val throwOnQuery: Boolean = false,
    ) : BillingGateway {
        private val _entitlementUpdates = MutableSharedFlow<Entitlement>(extraBufferCapacity = 4)
        override val purchaseUpdates: Flow<List<Purchase>> = flow { }
        // Override the resolved-entitlement push stream directly (bare states →
        // entitlementFromStates) so the test never needs a real Purchase.
        override val entitlementUpdates: Flow<Entitlement> = _entitlementUpdates

        /** Drive a live purchase-update emission from a bare PurchaseState int. */
        suspend fun emitPurchase(state: Int) {
            _entitlementUpdates.emit(entitlementFromStates(listOf(state)))
        }

        override suspend fun queryActiveSubscriptions(): List<Purchase>? = null
        override suspend fun queryActiveEntitlement(): Entitlement? {
            if (throwOnQuery) throw IllegalStateException("Play unavailable")
            return live
        }
        override suspend fun queryProductDetails(): List<ProductDetails> = emptyList()
        override fun launchBillingFlow(activity: Activity, productDetails: ProductDetails, offerToken: String) = false
    }

    /**
     * Gateway backed by a raw [Purchase] list so the interface's DEFAULT
     * [BillingGateway.queryActiveEntitlement] / [BillingGateway.queryHasPendingPremiumPurchase]
     * (the real [forPremiumProduct] choke point) execute unchanged. `subs == null`
     * models "could not query" (Play unavailable / non-OK).
     */
    private class FakePurchaseGateway(private val subs: List<Purchase>?) : BillingGateway {
        override val purchaseUpdates: Flow<List<Purchase>> = flow { }
        override suspend fun queryActiveSubscriptions(): List<Purchase>? = subs
        override suspend fun queryProductDetails(): List<ProductDetails> = emptyList()
        override fun launchBillingFlow(activity: Activity, productDetails: ProductDetails, offerToken: String) = false
    }
}
