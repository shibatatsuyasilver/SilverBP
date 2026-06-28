package com.silverbp.android.ui.paywall

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.silverbp.android.billing.BillingGateway
import com.silverbp.android.billing.EntitlementManager
import com.silverbp.android.billing.Entitlement
import com.silverbp.android.billing.PREMIUM_BASE_PLAN_MONTHLY
import com.silverbp.android.billing.PREMIUM_BASE_PLAN_YEARLY
import com.silverbp.android.billing.forPremiumProduct
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives [PaywallSheet]. Pulls the two subscription base plans
 * ([PREMIUM_BASE_PLAN_MONTHLY] / [PREMIUM_BASE_PLAN_YEARLY]) out of the live Play
 * [ProductDetails], exposes them as [PlanOption]s for the price cards, and
 * launches the purchase / restore flows.
 *
 * GRACEFUL DEGRADATION: on the emulator / before the product is published the
 * gateway returns no products. The state then settles on [Loading]=false with an
 * empty plan list and [available]=false so the sheet renders its "not available"
 * placeholder instead of crashing.
 *
 * No ViewModel-coupled persistence: entitlement caching lives in
 * [EntitlementManager]; this VM only reads product details and forwards purchase
 * results (the resolved tier propagates through the manager's StateFlow).
 */
class PaywallViewModel(
    private val gateway: BillingGateway = ServiceLocator.billingClient,
    private val entitlementManager: EntitlementManager = ServiceLocator.entitlementManager,
) : ViewModel() {

    /** A single buyable base plan, flattened from Play's nested offer model. */
    data class PlanOption(
        val basePlanId: String,
        /** Localized, currency-formatted recurring price (e.g. "NT$90"). */
        val formattedPrice: String,
        /** The offer token to pass to [BillingGateway.launchBillingFlow]. */
        val offerToken: String,
        /** True when this plan's selected offer includes a free-trial pricing phase. */
        val hasFreeTrial: Boolean,
    )

    data class UiState(
        val loading: Boolean = true,
        /** True once Play returned at least one usable plan. */
        val available: Boolean = false,
        val monthly: PlanOption? = null,
        val yearly: PlanOption? = null,
        /** One-shot user message key (restore result / pending / unavailable); cleared by [consumeMessage]. */
        val message: PaywallMessage? = null,
    )

    /** Transient outcomes surfaced to the sheet as a snackbar / inline note. */
    enum class PaywallMessage { RestoreFound, RestoreNone, Pending, Unavailable }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var productDetails: ProductDetails? = null

    init {
        loadPlans()
        observeFreshPendingPurchase()
    }

    /**
     * Surface a "payment pending" note the instant a FRESH purchase comes back
     * PENDING (slow card / cash / family-approval). The purchase-flow result
     * arrives asynchronously on [BillingGateway.purchaseUpdates]; a PENDING
     * premium purchase grants no entitlement, so the sheet does NOT dismiss and —
     * without this — the user gets no feedback right after tapping buy (#12).
     * Reuses the same pending choke point ([forPremiumProduct]) as the restore
     * path and the existing [PaywallMessage.Pending] copy (R.string.paywall_pending).
     * PURCHASED emissions are left to the entitlement stream (the sheet dismisses
     * on Premium); entitlement logic is unchanged.
     */
    private fun observeFreshPendingPurchase() {
        viewModelScope.launch {
            gateway.purchaseUpdates.collect { purchases ->
                val pending = purchases.forPremiumProduct()
                    .any { it.purchaseState == Purchase.PurchaseState.PENDING }
                if (pending) {
                    _state.value = _state.value.copy(message = PaywallMessage.Pending)
                }
            }
        }
    }

    /** (Re)query Play for the subscription product and rebuild the plan options. */
    fun loadPlans() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val details = runCatching { gateway.queryProductDetails() }.getOrDefault(emptyList())
            val product = details.firstOrNull()
            productDetails = product

            val monthly = product?.let { planOptionFor(it, PREMIUM_BASE_PLAN_MONTHLY) }
            val yearly = product?.let { planOptionFor(it, PREMIUM_BASE_PLAN_YEARLY) }
            _state.value = UiState(
                loading = false,
                available = monthly != null || yearly != null,
                monthly = monthly,
                yearly = yearly,
                message = _state.value.message,
            )
        }
    }

    /**
     * Launch the Play purchase sheet for [plan]. The *result* arrives
     * asynchronously on the billing listener → [EntitlementManager] re-resolves;
     * the sheet observes the manager's entitlement to dismiss on success. Returns
     * nothing here (fire-and-forget) — a failed launch surfaces [PaywallMessage.Unavailable].
     */
    fun launchPurchase(activity: Activity, plan: PlanOption) {
        val product = productDetails
        if (product == null) {
            _state.value = _state.value.copy(message = PaywallMessage.Unavailable)
            return
        }
        val launched = gateway.launchBillingFlow(activity, product, plan.offerToken)
        if (!launched) {
            _state.value = _state.value.copy(message = PaywallMessage.Unavailable)
        }
    }

    /**
     * "Restore purchases" — re-query live Play purchases and report a real result.
     *
     * The resolved tier is written back by [EntitlementManager.refresh]; here we
     * only pick a user-facing message, and only AFTER confirming the refresh
     * actually reached Play (finding #24). [EntitlementManager.refresh] returns
     * `false` when Play couldn't be queried (offline / non-OK) — surfacing
     * "no subscription found" off a stale cache in that case would be misleading,
     * so we show the distinct [PaywallMessage.Unavailable] ("try again later")
     * instead. On a confirmed query: Premium → RestoreFound; otherwise a PENDING
     * (slow card / cash) purchase → [PaywallMessage.Pending] (finding #25), else
     * RestoreNone.
     */
    fun restorePurchases() {
        viewModelScope.launch {
            val refreshed = runCatching { entitlementManager.refresh() }.getOrDefault(false)
            if (!refreshed) {
                // Couldn't reach Play — don't claim "no subscription" off the cache.
                _state.value = _state.value.copy(message = PaywallMessage.Unavailable)
                return@launch
            }
            val tier = runCatching { entitlementManager.entitlement.first() }
                .getOrDefault(Entitlement.Free)
            val message = if (tier == Entitlement.Premium) {
                PaywallMessage.RestoreFound
            } else {
                // No active entitlement: distinguish a payment still settling
                // (PENDING grants nothing yet) from "genuinely nothing to restore".
                val pending = runCatching { gateway.queryHasPendingPremiumPurchase() }
                    .getOrDefault(false)
                if (pending) PaywallMessage.Pending else PaywallMessage.RestoreNone
            }
            _state.value = _state.value.copy(message = message)
        }
    }

    /** Clear the one-shot [UiState.message] after the UI has shown it. */
    fun consumeMessage() {
        if (_state.value.message != null) {
            _state.value = _state.value.copy(message = null)
        }
    }

    /**
     * Flatten the matching base plan's *first* offer into a [PlanOption]. We take
     * the first offer Play returns for the base plan (Play orders eligible offers
     * best-first), read its recurring (non-zero) pricing phase for the displayed
     * price, and flag a free trial when any phase is a zero-price phase.
     */
    private fun planOptionFor(product: ProductDetails, basePlanId: String): PlanOption? {
        val offer = product.subscriptionOfferDetails
            ?.firstOrNull { it.basePlanId == basePlanId }
            ?: return null
        val phases = offer.pricingPhases.pricingPhaseList
        // The recurring price is the first phase whose price is > 0 (a free-trial
        // phase has priceAmountMicros == 0 and precedes it); fall back to the last
        // phase if every phase were free (shouldn't happen for a paid sub).
        val payingPhase = phases.firstOrNull { it.priceAmountMicros > 0L }
            ?: phases.lastOrNull()
            ?: return null
        val hasFreeTrial = phases.any { it.priceAmountMicros == 0L }
        return PlanOption(
            basePlanId = basePlanId,
            formattedPrice = payingPhase.formattedPrice,
            offerToken = offer.offerToken,
            hasFreeTrial = hasFreeTrial,
        )
    }
}
