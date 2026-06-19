package com.silverbp.android.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppMotion
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.MetricAccent
import com.silverbp.android.ui.theme.SilverBpTheme

/**
 * SilverBP M3 Expressive compact metric card — the latest reading for a single
 * metric (血糖 / 體重 on Today; reusable wherever a metric's latest value is
 * surfaced). Mirrors `.metric` in design/mockups/assets/app.css and the 血糖 /
 * 體重 tiles in design/mockups/01-today.html.
 *
 * Layout:
 * - Header row: a 40.dp rounded(12.dp) icon tile filled with [accent] at ~20%
 *   alpha + the [accent]-coloured [icon], the [title] (labelLarge), and an
 *   optional trailing [time] (labelMedium, onSurfaceVariant).
 * - Value row: [value] (headlineLarge, bold) + optional [unit] (labelLarge,
 *   onSurfaceVariant).
 * - Category row: an 8.dp [categoryColor] dot + [categoryLabel] (labelMedium,
 *   onSurfaceVariant) — shown only when [categoryLabel] is non-null.
 *
 * [accent] is the metric's fixed [MetricAccent] colour — the icon tile is the
 * SAME on every screen and NEVER varies by the reading's category status. The
 * varying status colour belongs to [categoryColor] / [categoryLabel].
 *
 * Pass [onClick] to make the whole card tappable (subtle press scale +
 * shape-morph per AppMotion); omit it for a static card. Pure UI: no state, no
 * ViewModel coupling.
 */
@Composable
fun MetricCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    value: String,
    unit: String? = null,
    time: String? = null,
    categoryColor: Color? = null,
    categoryLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onTimeClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Subtle, senior-friendly press feedback: a small scale-down + corner morph.
    val scale by animateFloatAsState(
        targetValue = if (onClick != null && pressed) 0.985f else 1f,
        animationSpec = AppMotion.springSnappy(),
        label = "metricCardScale",
    )
    val corner by animateDpAsState(
        targetValue = if (onClick != null && pressed) 20.dp else 28.dp,
        animationSpec = AppMotion.springDefault(),
        label = "metricCardCorner",
    )
    val shape = RoundedCornerShape(corner)

    val cardModifier = modifier
        .scale(scale)
        .heightIn(min = 150.dp)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
            } else {
                Modifier
            }
        )

    Card(
        modifier = cardModifier,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            // Header: accent icon tile + title, with the reading's time trailing.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                MetricIconTile(icon = icon, accent = accent)
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (time != null) {
                    if (onTimeClick != null) {
                        // Tappable count/"view all" affordance (e.g. "今天 3 筆") —
                        // distinct from the whole-card tap so the card itself still
                        // edits the shown reading. Primary-tinted to read as a link.
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onTimeClick)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    } else {
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Big value + optional unit.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.tight),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            // Category dot + label (the varying status colour).
            if (categoryLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                categoryColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                    )
                    Text(
                        text = categoryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Empty-state sibling of [MetricCard] — same surfaceContainer card and accent
 * icon tile, but no reading yet: shows the [title] and a "記一筆"-style [ctaText]
 * text button that calls [onClick]. Mirrors `.metric.empty` in
 * design/mockups/assets/app.css. The whole card is also tappable so the larger
 * (older-adult) target reaches the same action.
 */
@Composable
fun MetricCardEmpty(
    icon: ImageVector,
    accent: Color,
    title: String,
    ctaText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = AppMotion.springSnappy(),
        label = "metricCardEmptyScale",
    )
    val corner by animateDpAsState(
        targetValue = if (pressed) 20.dp else 28.dp,
        animationSpec = AppMotion.springDefault(),
        label = "metricCardEmptyCorner",
    )

    Card(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 150.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(corner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                MetricIconTile(icon = icon, accent = accent)
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            // The CTA button reaches the AA-min tap target on its own; the whole
            // card is tappable too for the larger, older-adult-friendly target.
            TextButton(onClick = onClick) {
                Text(ctaText)
            }
        }
    }
}

/**
 * The metric's accent icon tile: a 40.dp rounded(12.dp) square filled with
 * [accent] at ~20% alpha and the [accent]-coloured [icon]. Fixed per metric —
 * never tinted by a reading's category. Decorative (the title labels it), so the
 * icon carries no contentDescription.
 */
@Composable
private fun MetricIconTile(
    icon: ImageVector,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Preview(name = "MetricCard · dark", showBackground = true, backgroundColor = 0xFF0E0F13)
@Composable
private fun MetricCardPreview() {
    SilverBpTheme {
        Column(
            modifier = Modifier.padding(AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            MetricCard(
                icon = Icons.Filled.Bloodtype,
                accent = MetricAccent.Glucose,
                title = "血糖",
                value = "5.4",
                unit = "mmol/L",
                time = "07:15",
                categoryColor = Color(0xFF34C759),
                categoryLabel = "正常",
                onClick = {},
            )
            MetricCard(
                icon = Icons.Filled.MonitorWeight,
                accent = MetricAccent.Weight,
                title = "體重",
                value = "68.5",
                unit = "公斤",
                time = "昨天",
                categoryColor = Color(0xFF34C759),
                categoryLabel = "正常 · BMI 22.8",
                onClick = {},
            )
            MetricCardEmpty(
                icon = Icons.Filled.Bloodtype,
                accent = MetricAccent.Glucose,
                title = "血糖",
                ctaText = "記一筆",
                onClick = {},
            )
        }
    }
}
