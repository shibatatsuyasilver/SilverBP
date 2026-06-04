package com.silverbp.android.ui.exercise.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.achievements.AchievementStore
import com.silverbp.android.ui.achievements.MedalBadge
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing

/**
 * Home-screen card showing up to three most-recently-unlocked medals plus a
 * "view all" tap target. When the user has no unlocks yet, surfaces a
 * starter prompt so the affordance is discoverable from day one.
 *
 * Uses the shared [StandardCard] so its rounded corners / padding match the
 * other Exercise-tab cards; the three medal columns are equal-width with
 * centred, height-reserved labels so 5-6 character medal names line up cleanly
 * without ragged wrapping.
 */
@Composable
fun MedalShowcaseCard(
    state: AchievementStore.UiState,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StandardCard(
        modifier = modifier.clickable { onViewAll() },
        title = stringResource(R.string.medal_showcase_title),
        titleTrailing = {
            Text(
                stringResource(R.string.medal_showcase_view_all),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        if (state.recent.isEmpty()) {
            Text(
                stringResource(R.string.medal_showcase_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                state.recent.take(3).forEach { unlocked ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
                        modifier = Modifier.weight(1f),
                    ) {
                        MedalBadge(medal = unlocked.kind, unlocked = true, sizeDp = 56)
                        Text(
                            stringResource(unlocked.kind.displayNameRes),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
                        )
                    }
                }
                repeat(3 - state.recent.take(3).size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
