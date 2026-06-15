package com.silverbp.android.ui.premium

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.billing.Entitlement
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.paywall.PaywallViewModel
import com.silverbp.android.ui.paywall.openManageSubscription
import com.silverbp.android.ui.theme.PremiumGold

/** Design-system "Normal" green (#34C759) for the unlocked-state check marks. */
private val UnlockedGreen = Color(0xFF34C759)

/**
 * Full-screen Premium subscription page — the prominent, icon-led replacement for
 * the old plain Settings card. Opened from the Today top-bar crown
 * ([com.silverbp.android.ui.today.TodayScreen]) via the `premium` route.
 *
 * Reuses the existing billing stack: [PaywallViewModel] for the live Play plans +
 * purchase/restore, [openManageSubscription] for the Play deep link, and the real
 * [com.silverbp.android.billing.EntitlementManager.entitlement] to pick the state:
 *  - FREE → hero crown + 5 benefits + family note + month/year plan cards + lime
 *    Subscribe CTA + Restore + Manage.
 *  - PREMIUM → hero crown + "Premium — active" + unlocked list + Manage.
 *
 * Unlike the gate [com.silverbp.android.ui.paywall.PaywallSheet] this is not a
 * modal and does NOT auto-dismiss on purchase — it flips to the active state in
 * place so a subscriber sees a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumScreen(
    onClose: () -> Unit,
    vm: PaywallViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val entitlement by ServiceLocator.entitlementManager.entitlement.collectAsStateWithLifecycle()
    val isPremium = entitlement == Entitlement.Premium

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.paywall_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PremiumHero(isPremium = isPremium)

            if (isPremium) {
                ActiveState(onManage = { openManageSubscription(context) })
            } else {
                UpsellState(context = context, state = state, vm = vm)
            }
        }
    }
}

/** Centred lime crown + headline. Subtitle changes between upsell and active. */
@Composable
private fun PremiumHero(isPremium: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(PremiumGold.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = PremiumGold,
                modifier = Modifier.size(52.dp),
            )
        }
        Text(
            stringResource(
                if (isPremium) R.string.settings_premium_status_premium else R.string.paywall_title,
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (isPremium) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = UnlockedGreen,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.premium_active_thanks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                stringResource(R.string.paywall_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** FREE state: benefits + family note + plan cards + lime CTA + restore/manage. */
@Composable
private fun UpsellState(
    context: Context,
    state: PaywallViewModel.UiState,
    vm: PaywallViewModel,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    val effectiveSelected = selected ?: state.yearly?.basePlanId ?: state.monthly?.basePlanId

    BenefitsCard(
        benefits = listOf(
            stringResource(R.string.paywall_benefit_members),
            stringResource(R.string.paywall_benefit_glucose),
            stringResource(R.string.paywall_benefit_pdf),
            stringResource(R.string.paywall_benefit_ai_coach),
            stringResource(R.string.paywall_benefit_ai_chat),
        ),
    )

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
            }

            // Lime hero CTA (matches the app's primary onboarding button).
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    stringResource(R.string.paywall_subscribe),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }

    TextButton(onClick = { vm.restorePurchases() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.paywall_restore))
    }
    TextButton(onClick = { openManageSubscription(context) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.paywall_manage))
    }

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

/** PREMIUM state: the unlocked-features list + a Manage button. No plan cards. */
@Composable
private fun ActiveState(onManage: () -> Unit) {
    BenefitsCard(
        title = stringResource(R.string.premium_unlocked_title),
        benefits = listOf(
            stringResource(R.string.paywall_benefit_members),
            stringResource(R.string.paywall_benefit_glucose),
            stringResource(R.string.paywall_benefit_pdf),
            stringResource(R.string.paywall_benefit_ai_coach),
            stringResource(R.string.paywall_benefit_ai_chat),
        ),
        checkTint = UnlockedGreen,
    )
    OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(stringResource(R.string.paywall_manage))
    }
}

/** A surface card listing benefit rows, each with a coloured check. */
@Composable
private fun BenefitsCard(
    benefits: List<String>,
    title: String? = null,
    checkTint: Color = MaterialTheme.colorScheme.primary,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            benefits.forEach { BenefitRow(it, checkTint) }
        }
    }
}

@Composable
private fun BenefitRow(text: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/**
 * A selectable price card — same a11y posture as the paywall sheet's: [selectable]
 * with [Role.RadioButton], a single merged contentDescription (plan + price) so the
 * badges/price aren't read piecemeal (audit M31).
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

/** Walk the ContextWrapper chain to the hosting Activity for the billing flow. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
