package com.silverbp.android.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin wrapper over the Play Billing 8 [BillingClient], scoped to the single
 * subscription product [PREMIUM_PRODUCT_ID]. Concerns it owns:
 *  - connection lifecycle with bounded retry/backoff (Play auto-reconnect is on
 *    too, but we also gate every call behind [ensureConnected] so a cold call
 *    waits for the first connect),
 *  - querying product details (base plans / offers) and active subscriptions,
 *  - launching the purchase flow,
 *  - receiving purchase updates ([PurchasesUpdatedListener]) and re-broadcasting
 *    them on [purchaseUpdates],
 *  - acknowledging PURCHASED-but-unacknowledged subs (else Play auto-refunds at
 *    72 h),
 *  - ignoring PENDING purchases for entitlement (handled by
 *    [activeEntitlementFromPurchases]).
 *
 * GRACEFUL DEGRADATION: on the emulator / a device without Play, or before the
 * product is published, connection or queries fail. Every public method swallows
 * those failures and returns an empty result instead of throwing — the app must
 * run identically with Billing returning nothing. See [EntitlementManager].
 *
 * Callback-style async APIs are wrapped in [suspendCancellableCoroutine] rather
 * than the billing-ktx coroutine extensions so the exact result types are pinned
 * here and don't drift with the ktx shape across 8.x point releases.
 */
class BillingClientWrapper(
    context: Context,
    // Process-lifetime scope; defaults to an IO supervisor so a dropped UI scope
    // never cancels an in-flight acknowledge. ServiceLocator passes the app scope.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BillingGateway {

    private val _purchaseUpdates = MutableSharedFlow<List<Purchase>>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    override val purchaseUpdates: SharedFlow<List<Purchase>> = _purchaseUpdates.asSharedFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            // Acknowledge any newly-completed sub so Play doesn't auto-refund it,
            // then broadcast the live set so the manager can re-resolve.
            scope.launch { acknowledgeIfNeeded(purchases) }
            _purchaseUpdates.tryEmit(purchases)
        } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            // No-op: the user backed out of the sheet; entitlement is unchanged.
            Log.d(TAG, "[Billing] purchase flow cancelled by user")
        } else {
            Log.w(TAG, "[Billing] purchase update error: ${result.responseCode} ${result.debugMessage}")
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    // ============================================================
    // Connection
    // ============================================================

    /**
     * Suspend until the client is connected, with bounded retry/backoff. Returns
     * true once READY, false if every attempt failed (Play unavailable). Never
     * throws — a false here makes the caller return an empty result.
     */
    private suspend fun ensureConnected(): Boolean {
        if (client.connectionState == BillingClient.ConnectionState.CONNECTED) return true
        var backoffMs = INITIAL_BACKOFF_MS
        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            val ok = runCatching { startConnectionOnce() }.getOrDefault(false)
            if (ok) return true
            if (attempt < MAX_CONNECT_ATTEMPTS - 1) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        Log.i(TAG, "[Billing] connection unavailable after $MAX_CONNECT_ATTEMPTS attempts — degrading to empty")
        return false
    }

    private suspend fun startConnectionOnce(): Boolean = suspendCancellableCoroutine { cont ->
        // Already connected by the time we got scheduled (auto-reconnect raced us).
        if (client.connectionState == BillingClient.ConnectionState.CONNECTED) {
            if (cont.isActive) cont.resume(true)
            return@suspendCancellableCoroutine
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                // Auto-reconnection is enabled; nothing to do. If this fires
                // before setup finished, the coroutine is resumed false by the
                // surrounding retry loop's next attempt.
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    // ============================================================
    // Product details
    // ============================================================

    override suspend fun queryProductDetails(): List<ProductDetails> {
        if (!ensureConnected()) return emptyList()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PREMIUM_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()

        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(queryResult.productDetailsList)
                } else {
                    Log.i(TAG, "[Billing] queryProductDetails empty: ${result.responseCode} ${result.debugMessage}")
                    cont.resume(emptyList())
                }
            }
        }
    }

    // ============================================================
    // Purchases
    // ============================================================

    override suspend fun queryActiveSubscriptions(): List<Purchase>? {
        // Connection unavailable → null ("could NOT query"), NOT empty ("no subs").
        // The manager keeps the last-known cache on null so a transient Play hiccup
        // never downgrades a paying subscriber to Free (finding round 1, #1).
        if (!ensureConnected()) return null
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        // null = query came back non-OK (couldn't determine); empty list = OK with
        // no active subs. Distinguished so refresh() only persists a downgrade to
        // Free on a CONFIRMED OK query that genuinely returned zero subs.
        val queried: List<Purchase>? = suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, list ->
                if (!cont.isActive) return@queryPurchasesAsync
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(list)
                } else {
                    Log.i(TAG, "[Billing] queryPurchases unavailable: ${result.responseCode} ${result.debugMessage}")
                    cont.resume(null)
                }
            }
        }
        val purchases: List<Purchase> = queried ?: return null
        // Opportunistically acknowledge anything that completed while we were
        // away (e.g. purchased on another device, restored here), and surface
        // the live set so a manual refresh updates UI immediately.
        acknowledgeIfNeeded(purchases)
        _purchaseUpdates.tryEmit(purchases)
        return purchases
    }

    override fun launchBillingFlow(
        activity: Activity,
        productDetails: ProductDetails,
        offerToken: String,
    ): Boolean {
        val productParams = com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()
        val flowParams = com.android.billingclient.api.BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = client.launchBillingFlow(activity, flowParams)
        val launched = result.responseCode == BillingClient.BillingResponseCode.OK
        if (!launched) {
            Log.w(TAG, "[Billing] launchBillingFlow failed: ${result.responseCode} ${result.debugMessage}")
        }
        return launched
    }

    // ============================================================
    // Acknowledgement
    // ============================================================

    /**
     * Acknowledge every PURCHASED-but-unacknowledged sub. Play auto-refunds an
     * unacknowledged purchase after 72 h, so this must run whenever we see a
     * fresh purchase or restore one on startup. PENDING purchases are skipped —
     * they aren't acknowledgeable and grant no entitlement yet.
     */
    private suspend fun acknowledgeIfNeeded(purchases: List<Purchase>) {
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            if (purchase.isAcknowledged) continue
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            val done = CompletableDeferred<Unit>()
            client.acknowledgePurchase(params) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "[Billing] acknowledge failed: ${result.responseCode} ${result.debugMessage}")
                }
                done.complete(Unit)
            }
            runCatching { done.await() }
        }
    }

    private companion object {
        const val TAG = "BillingClientWrapper"
        const val MAX_CONNECT_ATTEMPTS = 3
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 4_000L
    }
}
