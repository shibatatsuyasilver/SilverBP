package com.silverbp.android.ui.exercise.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.achievements.AchievementStore
import com.silverbp.android.ui.achievements.MedalBadge

/**
 * Home-screen card showing up to three most-recently-unlocked medals plus a
 * "view all" tap target. When the user has no unlocks yet, surfaces a
 * starter prompt so the affordance is discoverable from day one.
 */
@Composable
fun MedalShowcaseCard(
    state: AchievementStore.UiState,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onViewAll() },
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.medal_showcase_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.medal_showcase_view_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.recent.isEmpty()) {
                Text(
                    stringResource(R.string.medal_showcase_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.recent.take(3).forEach { unlocked ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            MedalBadge(medal = unlocked.kind, unlocked = true, sizeDp = 56)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                stringResource(unlocked.kind.displayNameRes),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
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
}
