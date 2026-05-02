package com.silverbp.android.ui.coach.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Compact circular progress ring with a percent label centered.
 *
 * Used by [ModuleCard] to summarise per-module weekly adherence. The ring
 * track uses surfaceVariant; the active arc uses primary.
 */
@Composable
fun GoalRing(
    ratio: Float,
    modifier: Modifier = Modifier,
    sizeDp: Int = 56,
) {
    val safeRatio = ratio.coerceIn(0f, 1f)
    Box(
        modifier = modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { safeRatio },
            modifier = Modifier.size(sizeDp.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = ProgressIndicatorDefaults.CircularStrokeWidth,
        )
        Text(
            "${(safeRatio * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
