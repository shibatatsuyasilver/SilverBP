package com.silverbp.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R

/**
 * The shared "進階" / "Premium" pill rendered on gated rows (e.g. the
 * "add member" affordance, the PDF detail toggle) so a free user can see at a
 * glance which affordances live behind the subscription.
 *
 * Pure UI: no state, no entitlement coupling. The caller decides *whether* to
 * show it (typically `if (!entitlementManager.isPremium())`). The pill carries
 * its own [R.string.premium_badge_cd] contentDescription (audit M31) and merges
 * its child Text's semantics so TalkBack announces it once.
 */
@Composable
fun PremiumBadge(modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.premium_badge_cd)
    Text(
        text = stringResource(R.string.premium_badge_label),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = cd },
    )
}
