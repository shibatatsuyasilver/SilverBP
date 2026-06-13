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

    // ---------------------------------------------------------------- fakes ----

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
}
