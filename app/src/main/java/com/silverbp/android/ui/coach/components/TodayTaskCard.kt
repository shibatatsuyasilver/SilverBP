package com.silverbp.android.ui.coach.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.ui.coach.TodayTaskUi
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.HeroCard
import com.silverbp.android.ui.components.HeroForeground
import com.silverbp.android.ui.components.HeroForegroundDim
import com.silverbp.android.ui.components.HeroLabel
import com.silverbp.android.ui.theme.AppSpacing

@Composable
fun TodayTaskCard(
    task: TodayTaskUi,
    onStartExercise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The safety-hold alert is a standalone error banner, not a "task" — render it
    // on its own (the hero gradient reads as positive/encouraging, which would be
    // the wrong tone for a hold).
    if (task.safetyHold) {
        SafetyHoldBanner(modifier = modifier)
        return
    }

    HeroCard(modifier = modifier) {
        HeroLabel(text = stringResource(R.string.coach_today_task_title))

        if (task.isRestDay) {
            HeroBanner(
                icon = Icons.Filled.CheckCircle,
                text = stringResource(R.string.coach_today_task_rest_day),
            )
            if (task.achievedMinutes > 0) {
                Text(
                    stringResource(
                        R.string.coach_today_task_rest_day_progress,
                        task.achievedMinutes,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeroForegroundDim,
                )
            }
            return@HeroCard
        }

        Text(
            task.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = HeroForeground,
        )
        task.subtitle?.let { sub ->
            Text(
                sub,
                style = MaterialTheme.typography.bodyLarge,
                color = HeroForegroundDim,
            )
        }

        if (task.targetMinutes > 0) {
            val progress = (task.achievedMinutes.toFloat() / task.targetMinutes).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                color = HeroForeground,
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.itemGap),
            )
        }

        if (task.completed) {
            HeroBanner(
                icon = Icons.Filled.CheckCircle,
                text = stringResource(R.string.coach_today_task_done_today, task.achievedMinutes),
            )
        } else {
            // The "x / y 分鐘" label and the "去散步" CTA share a single row, as in
            // design/mockups/04-coach.html — not a full-width button below the bar.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (task.targetMinutes > 0) {
                    Text(
                        stringResource(
                            R.string.coach_today_task_progress,
                            task.achievedMinutes,
                            task.targetMinutes,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = HeroForegroundDim,
                    )
                } else {
                    Spacer(Modifier.size(0.dp))
                }
                ExpressiveSecondaryButton(
                    text = stringResource(R.string.coach_today_task_go_walk),
                    onClick = onStartExercise,
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                )
            }
        }
    }
}

/**
 * A translucent white pill-banner inside the hero (e.g. rest-day / completed),
 * keeping the white-on-gradient hero tone instead of an opaque tertiaryContainer.
 */
@Composable
private fun HeroBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(AppSpacing.cardCorner),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = HeroForeground)
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = HeroForeground,
        )
    }
}

@Composable
private fun SafetyHoldBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(AppSpacing.heroCorner),
            )
            .padding(AppSpacing.cardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.tight)) {
                Text(
                    stringResource(R.string.coach_safety_hold_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(R.string.coach_safety_hold_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
