package com.silverbp.android.ui.paywall

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.silverbp.android.R

/**
 * Why the paywall was opened — drives the contextual header shown at the top of
 * [PaywallSheet]. Each value maps to a `gate_*_title` string so a free user who
 * tapped a gated action sees *which* feature needs Premium. [Generic] is used
 * for the "Upgrade to Premium" entry point in Settings (no specific gate).
 */
enum class GateReason(@StringRes val titleRes: Int?) {
    /** Settings "Upgrade" button / no specific gated action. */
    Generic(null),
    AddMember(R.string.gate_member_title),
    PdfDetail(R.string.gate_pdf_detail_title),
    AiCoach(R.string.gate_ai_coach_title),
    AiChat(R.string.gate_ai_chat_title),
}

/**
 * The single, app-wide handle a gate call-site uses to surface the paywall. It is
 * provided by [PaywallHost] (mounted once over the whole nav graph) and read via
 * [LocalPaywallController]. Gates do NOT host their own sheet — they just call:
 *
 * ```
 * val paywall = LocalPaywallController.current
 * // on the gated onClick:
 * if (!ServiceLocator.entitlementManager.isPremium()) { paywall.show(GateReason.AddMember); return }
 * // …premium action…
 * ```
 *
 * One hoisted sheet keeps the Billing/ViewModel wiring in exactly one place and
 * means the Gates agent only depends on this tiny stable surface.
 */
@Stable
interface PaywallController {
    /** Open the paywall sheet, optionally tagged with the [reason] that triggered it. */
    fun show(reason: GateReason = GateReason.Generic)

    /** Close the paywall sheet (used by the sheet's own Close button / dismiss). */
    fun dismiss()
}

/**
 * No-op fallback so previews / screens rendered outside [PaywallHost] don't crash
 * when they read [LocalPaywallController]. In the real app the host always
 * provides a working controller.
 */
private object NoopPaywallController : PaywallController {
    override fun show(reason: GateReason) = Unit
    override fun dismiss() = Unit
}

/** App-wide access to the hoisted paywall. Defaults to a no-op outside [PaywallHost]. */
val LocalPaywallController = compositionLocalOf<PaywallController> { NoopPaywallController }

/**
 * Mounts the single app-wide paywall over [content] and provides a
 * [PaywallController] down the tree via [LocalPaywallController]. Place this once,
 * wrapping the whole nav graph (see [com.silverbp.android.ui.SilverBpApp]), so any
 * screen can trigger the sheet without threading callbacks.
 *
 * The sheet itself is only composed while visible, so the [PaywallViewModel] +
 * Billing product query are kick-started lazily on first [PaywallController.show].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallHost(content: @Composable () -> Unit) {
    // Held as raw MutableState (not `by`) so the remembered controller object can
    // mutate them from its methods without capturing a delegated local var.
    val visibleState = remember { mutableStateOf(false) }
    val reasonState = remember { mutableStateOf(GateReason.Generic) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val controller = remember {
        object : PaywallController {
            override fun show(reason: GateReason) {
                reasonState.value = reason
                visibleState.value = true
            }

            override fun dismiss() {
                visibleState.value = false
            }
        }
    }

    CompositionLocalProvider(LocalPaywallController provides controller) {
        content()
    }

    val visible by visibleState
    if (visible) {
        PaywallSheet(
            reason = reasonState.value,
            sheetState = sheetState,
            onDismiss = { visibleState.value = false },
        )
    }
}
