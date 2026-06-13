package com.silverbp.android.billing

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.flow.Flow

/**
 * The minimal Play-Billing surface [EntitlementManager] depends on. Pulling it
 * behind an interface keeps the manager unit-testable on the JVM (no real
 * BillingClient, no Play services) — tests supply a [FakeBillingGateway] while
 * production wires [BillingClientWrapper].
 *
 * Everything here is "graceful when Play is unavailable": on the emulator (no
 * products configured) [queryActiveSubscriptions] / [queryProductDetails] return
 * empty lists and never throw, so the manager simply resolves to [Entitlement.Free]
 * (which, with PREMIUM_ENFORCED=false, still means everything is unlocked).
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
     * Query the active subscription purchases for "silverbp_premium". Returns an
     * empty list when Play is unavailable, the connection can't be made, or no
     * products are configured — never throws.
     */
    suspend fun queryActiveSubscriptions(): List<Purchase>

    /**
     * Resolve the live entitlement (the [queryActiveSubscriptions] result mapped
     * through [activeEntitlementFromPurchases]). This is what [EntitlementManager]
     * consumes so it never touches a raw [Purchase] — keeping [Purchase] (whose
     * ctor JSON-parses) out of the manager makes the manager JVM-unit-testable
     * with a trivial fake. Returns [Entitlement.Free] when Play is unavailable.
     */
    suspend fun queryActiveEntitlement(): Entitlement =
        activeEntitlementFromPurchases(queryActiveSubscriptions())

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
