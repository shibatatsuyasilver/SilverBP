package com.silverbp.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.silverbp.android.ui.theme.AppSpacing

/**
 * The Stitch-aligned rounded content card used across the polished UI.
 *
 * A more generously-spaced, larger-radius sibling of [SectionCard] (which is
 * intentionally left untouched because several out-of-scope screens depend on
 * its exact look). Use this for grouped content blocks, list-group containers,
 * and hero cards.
 *
 * - Pass [title] for a section-style card with a semibold heading; omit it for
 *   a plain content card (e.g. a hero reading, a list group).
 * - [titleTrailing] renders an action (e.g. "View all") aligned to the end of
 *   the title row.
 * - [content] runs in a [ColumnScope] so callers can use weight/alignment.
 *
 * Pure UI: no state, no ViewModel coupling.
 */
@Composable
fun StandardCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    contentPadding: Dp = AppSpacing.cardPadding,
    cornerRadius: Dp = AppSpacing.cardCorner,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AppSpacing.itemGap),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
        ) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    titleTrailing?.invoke()
                }
            }
            content()
        }
    }
}
