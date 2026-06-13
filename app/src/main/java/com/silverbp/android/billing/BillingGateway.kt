package com.silverbp.android.billing

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The minimal Play-Billing surface [EntitlementManager] depends on. Pulling it
 * behind an interface keeps the manager unit-testable on the JVM (no real
 * BillingClient, no Play services) — tests supply a [FakeBillingGateway] while
 * production wires [BillingClientWrapper].
 *
 * Everything here is "graceful when Play is unavailable": on the emulator (no
 * products configured) [queryActiveSubscriptions] / [queryProductDetails] return
 * empty/null and never throw, so the manager keeps the last-known cache instead
 * of clobbering Premium with Free (see [queryActiveEntitlement] / finding round 1).
 */
interface BillingGateway {

    /**
     * Emits whenever the live purchase set changes — both from the
     * PurchasesUpdatedListener (a freshly-completed flow) and from explicit
     * [queryActiveSubscriptions] refreshes. Only *acknowledged-or-pending-then-
     * PURCHASED* subs that grant entitlement are reflected; PENDING purchases
     * grant nothing (see [activeEntitlementFromPurchases]).
     */
    val purchaseUpdates: Flow<List<Purchase>>

    /**
     * The live [purchaseUpdates] set mapped to a resolved [Entitlement] so
     * [EntitlementManager] can react to a freshly-completed purchase WITHOUT ever
     * touching a raw [Purchase] (whose ctor JSON-parses — keeping it out of the
     * manager keeps the manager JVM-unit-testable). A PURCHASED sub maps to
     * Premium; PENDING/UNSPECIFIED to Free (see [activeEntitlementFromPurchases]).
     *
     * NOTE: this is a *positive-signal* stream — every emission here is a real,
     * just-observed purchase set, so unlike [queryActiveEntitlement] it never
     * needs a "couldn't query" sentinel. The manager promotes to Premium on a
     * Premium emission; it does NOT downgrade to Free off this stream (a real
     * downgrade is confirmed only by a successful [queryActiveEntitlement]).
     */
    val entitlementUpdates: Flow<Entitlement>
        get() = purchaseUpdates.map { activeEntitlementFromPurchases(it) }

    /**
     * Query the active subscription purchases for "silverbp_premium". Returns
     * `null` when Play is unavailable / the connection can't be made / the query
     * came back non-OK (i.e. "could NOT determine"), an empty list when the query
     * succeeded but found no active subs, and a non-empty list otherwise. Never
     * throws. The null-vs-empty distinction lets [queryActiveEntitlement] avoid
     * downgrading a paying subscriber to Free on a transient Play hiccup.
     */
    suspend fun queryActiveSubscriptions(): List<Purchase>?

    /**
     * Resolve the live entitlement from [queryActiveSubscriptions]. Returns:
     *  - `null` when Play could NOT be queried (unavailable / non-OK) — the caller
     *    ([EntitlementManager.refresh]) must then KEEP the last-known cache rather
     *    than overwrite Premium with Free (finding round 1, #1).
     *  - [Entitlement.Premium] / [Entitlement.Free] on a CONFIRMED query result
     *    (mapped through [activeEntitlementFromPurchases]; PENDING grants nothing).
     *
     * Kept off raw [Purchase] so the manager stays JVM-unit-testable with a
     * trivial fake.
     */
    suspend fun queryActiveEntitlement(): Entitlement? =
        queryActiveSubscriptions()?.let { activeEntitlementFromPurchases(it) }

    /**
     * Resolve the configured base plans / offers for "silverbp_premium". Empty
     * when Play has no products (emulator / not-yet-published) — never throws.
     */
    suspend fun queryProductDetails(): List<ProductDetails>

    /**
     * Launch the Play purchase sheet for the chosen [productDetails] + [offerToken].
     * Returns true if the flow was launched (the *result* arrives later on
     * [purchaseUpdates]); false if Billing was unavailable.
     */
    fun launchBillingFlow(activity: Activity, productDetails: ProductDetails, offerToken: String): Boolean
}

/**
 * The single subscription product id (see plan + RELEASE.md). Base plans
 * [PREMIUM_BASE_PLAN_MONTHLY] / [PREMIUM_BASE_PLAN_YEARLY] hang off this one
 * product.
 */
const val PREMIUM_PRODUCT_ID: String = "silverbp_premium"

/** Monthly base plan id configured under [PREMIUM_PRODUCT_ID] in Play Console. */
const val PREMIUM_BASE_PLAN_MONTHLY: String = "premium-monthly"

/** Yearly base plan id (carries the 7-day free-trial offer) under [PREMIUM_PRODUCT_ID]. */
const val PREMIUM_BASE_PLAN_YEARLY: String = "premium-yearly"

/**
 * Maps a raw Play purchase list to a resolved [Entitlement]. A purchase grants
 * Premium ONLY when its state is [Purchase.PurchaseState.PURCHASED]; PENDING
 * (slow card / cash payment) grants nothing until it later transitions to
 * PURCHASED. Acknowledgement is orthogonal — an unacknowledged PURCHASED sub is
 * still entitled (we acknowledge it asynchronously to avoid the 3-day auto-refund),
 * so we do NOT require `isAcknowledged` here.
 *
 * Shared by the wrapper and the manager so the "what counts as Premium" rule
 * lives in exactly one place and is unit-tested directly.
 */
fun activeEntitlementFromPurchases(purchases: List<Purchase>): Entitlement =
    entitlementFromStates(purchases.map { it.purchaseState })

/**
 * Pure rule (state ints only) behind [activeEntitlementFromPurchases]. Split out
 * so the PENDING-grants-nothing invariant is unit-testable on the JVM without
 * constructing a real (JSON-parsing) [Purchase]. Expects
 * [Purchase.PurchaseState] ints: PURCHASED=1 grants Premium; PENDING=2 and
 * UNSPECIFIED=0 grant nothing.
 */
fun entitlementFromStates(states: List<Int>): Entitlement =
    if (states.any { it == Purchase.PurchaseState.PURCHASED }) Entitlement.Premium else Entitlement.Free
