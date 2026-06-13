package com.silverbp.android.ui.paywall

import android.app.Activity
import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.billing.Entitlement
import com.silverbp.android.billing.PREMIUM_PRODUCT_ID
import com.silverbp.android.di.ServiceLocator

/**
 * The subscription paywall, shown as a [ModalBottomSheet] over whatever screen
 * triggered it (mirrors [com.silverbp.android.ui.member.MemberEditorSheet]'s
 * structure: scrollable Column, navigation-bar padding, big elderly-friendly
 * type). Hosted once by [PaywallHost]; opened via [PaywallController.show].
 *
 * Layout (top→bottom):
 *  - contextual gate header (only when [reason] names a gate),
 *  - title + family-payer framing subtitle,
 *  - benefit bullets,
 *  - two price cards (monthly / yearly — yearly badged "best value" + the
 *    7-day-trial chip when Play reports a trial offer),
 *  - Subscribe (launches the selected plan's Play flow),
 *  - Restore purchases + Manage subscription deep link,
 *  - graceful "not available" placeholder when Play returned no products.
 *
 * Auto-dismisses once [EntitlementManager.entitlement] flips to Premium (the
 * purchase listener resolves asynchronously after the Play sheet completes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(
    reason: GateReason,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    vm: PaywallViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val entitlement by ServiceLocator.entitlementManager.entitlement.collectAsStateWithLifecycle()

    // Selected plan: default to yearly (best value) once available, else monthly.
    var selected by remember { mutableStateOf<String?>(null) }
    val effectiveSelected = selected
        ?: state.yearly?.basePlanId
        ?: state.monthly?.basePlanId

    // Close automatically the moment the real entitlement resolves to Premium —
    // covers a successful purchase as well as a "restore" that found a sub.
    LaunchedEffect(entitlement) {
        if (entitlement == Entitlement.Premium) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Contextual gate header — only when arriving from a specific gate.
            reason.titleRes?.let { titleRes ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.gate_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                stringResource(R.string.paywall_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.paywall_subtitle),
                style = MaterialTheme.typography.bodyLarge,
            )

            // Benefit bullets.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BenefitRow(stringResource(R.string.paywall_benefit_members))
                BenefitRow(stringResource(R.string.paywall_benefit_pdf))
                BenefitRow(stringResource(R.string.paywall_benefit_ai))
            }

            // Family-payer framing (the adult child pays for the elder).
            Text(
                stringResource(R.string.paywall_family_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                state.loading -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.paywall_loading))
                    }
                }

                !state.available -> {
                    // Emulator / not-yet-published: no products. Never crash —
                    // show a graceful note and still offer "restore".
                    Text(
                        stringResource(R.string.paywall_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.selectableGroup(),
                    ) {
                        state.monthly?.let { plan ->
                            PlanCard(
                                planLabel = stringResource(R.string.paywall_plan_monthly),
                                priceText = stringResource(R.string.paywall_plan_price_monthly, plan.formattedPrice),
                                bestValue = false,
                                freeTrial = plan.hasFreeTrial,
                                selected = effectiveSelected == plan.basePlanId,
                                onSelect = { selected = plan.basePlanId },
                            )
                        }
                        state.yearly?.let { plan ->
                            PlanCard(
                                planLabel = stringResource(R.string.paywall_plan_yearly),
                                priceText = stringResource(R.string.paywall_plan_price_yearly, plan.formattedPrice),
                                bestValue = true,
                                freeTrial = plan.hasFreeTrial,
                                selected = effectiveSelected == plan.basePlanId,
                                onSelect = { selected = plan.basePlanId },
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val plan = when (effectiveSelected) {
                                state.yearly?.basePlanId -> state.yearly
                                state.monthly?.basePlanId -> state.monthly
                                else -> null
                            } ?: return@Button
                            context.findActivity()?.let { vm.launchPurchase(it, plan) }
                        },
                        enabled = effectiveSelected != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.paywall_subscribe))
                    }
                }
            }

            // Restore + manage are always available (an existing subscriber may
            // open this even when product details didn't load).
            TextButton(
                onClick = { vm.restorePurchases() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.paywall_restore))
            }
            TextButton(
                onClick = { openManageSubscription(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.paywall_manage))
            }

            // One-shot messages (restore result / unavailable) as an inline note;
            // simplest robust surface inside a bottom sheet (no Scaffold here).
            state.message?.let { msg ->
                val msgRes = when (msg) {
                    PaywallViewModel.PaywallMessage.RestoreFound -> R.string.paywall_restore_found
                    PaywallViewModel.PaywallMessage.RestoreNone -> R.string.paywall_restore_none
                    PaywallViewModel.PaywallMessage.Pending -> R.string.paywall_pending
                    PaywallViewModel.PaywallMessage.Unavailable -> R.string.paywall_unavailable
                }
                Text(
                    stringResource(msgRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/**
 * A selectable price card. Uses [selectable] with [Role.RadioButton] so TalkBack
 * announces it as a radio option; the whole card carries a single merged
 * contentDescription (plan + price) so the badges/price don't get read out
 * piecemeal (audit M31).
 */
@Composable
private fun PlanCard(
    planLabel: String,
    priceText: String,
    bestValue: Boolean,
    freeTrial: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val cardCd = stringResource(R.string.paywall_plan_card_cd, planLabel, priceText)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp),
            )
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = cardCd
            },
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    planLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (bestValue) PlanChip(stringResource(R.string.paywall_plan_best_value))
            }
            Text(priceText, style = MaterialTheme.typography.headlineSmall)
            if (freeTrial) {
                Text(
                    stringResource(R.string.paywall_plan_free_trial),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PlanChip(text: String) {
    // The plan + price is already announced by the card's merged semantics, so
    // the chip text is decorative for TalkBack (cleared).
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clearAndSetSemantics {},
    )
}

/**
 * Deep-link to the Play subscription management page for this product. Shared by
 * the paywall's "Manage subscription" button and the Settings Premium card's
 * manage CTA (so an existing subscriber goes straight to Play, not via the sheet).
 */
fun openManageSubscription(context: Context) {
    val uri = Uri.parse(
        "https://play.google.com/store/account/subscriptions" +
            "?sku=$PREMIUM_PRODUCT_ID&package=${context.packageName}",
    )
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}

/** Walk the ContextWrapper chain to the hosting Activity for the billing flow. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
