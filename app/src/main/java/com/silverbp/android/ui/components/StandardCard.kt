package com.silverbp.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppMotion
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.MetricAccent
import com.silverbp.android.ui.theme.SilverBpTheme

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
 * - [onClick], when non-null, makes the whole card tappable with a subtle
 *   press scale (~0.985, mirrors the `.card.tappable:active` mockup rule).
 * - [accent], when set, draws a slim leading colour stripe (e.g. a
 *   [MetricAccent] tint) down the card's leading edge.
 *
 * Defaults follow Material 3 Expressive: container = surfaceContainer and a
 * large (28dp) corner radius via [MaterialTheme.shapes]. Pass [cornerRadius] to
 * override (e.g. hero cards).
 *
 * Pure UI: no state, no ViewModel coupling.
 */
@Composable
fun StandardCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    contentPadding: Dp = AppSpacing.cardPadding,
    // Extra start inset for the title only, on top of [contentPadding]. Used by
    // groups that deliberately run a small [contentPadding] (so rows/dividers sit
    // close to the card edges) but still want the heading to line up with the
    // rows' own inner padding instead of hugging the card edge.
    titleStartPadding: Dp = 0.dp,
    cornerRadius: Dp = LargeCardCorner,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AppSpacing.itemGap),
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)

    // Whole-card press feedback: a subtle scale-down only when the card is
    // tappable. Senior-friendly — the dip is small (0.985) and snappy.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (onClick != null && pressed) 0.985f else 1f,
        animationSpec = AppMotion.springSnappy(),
        label = "StandardCardPressScale",
    )

    val cardModifier = modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }

    val cardColors = CardDefaults.cardColors(containerColor = containerColor)

    val body: @Composable () -> Unit = {
        StandardCardBody(
            title = title,
            titleTrailing = titleTrailing,
            contentPadding = contentPadding,
            titleStartPadding = titleStartPadding,
            accent = accent,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = shape,
            colors = cardColors,
            interactionSource = interactionSource,
        ) { body() }
    } else {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = cardColors,
        ) { body() }
    }
}

@Composable
private fun StandardCardBody(
    title: String?,
    titleTrailing: (@Composable () -> Unit)?,
    contentPadding: Dp,
    titleStartPadding: Dp,
    accent: Color?,
    verticalArrangement: Arrangement.Vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (accent != null) {
            // A clean rectangle; the parent Card clips it to the card's rounded
            // leading corners, so it reads as a slim accent stripe.
            Box(
                modifier = Modifier
                    .width(AccentStripeWidth)
                    .fillMaxHeight()
                    .background(accent),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
        ) {
            if (title != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = titleStartPadding),
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

/** Width of the optional leading [accent] stripe. */
private val AccentStripeWidth = 4.dp

/**
 * The card's default corner — the M3 Expressive "large" radius from
 * [MaterialTheme.shapes] (AppShapes.large = 28dp). Read from the theme so the
 * default tracks the shape scale; callers may still pass an explicit
 * `cornerRadius` (e.g. hero cards) to override.
 */
private val LargeCardCorner: Dp
    @Composable
    @ReadOnlyComposable
    get() = (MaterialTheme.shapes.large as? RoundedCornerShape)
        ?.topStart
        ?.let { corner ->
            with(LocalDensity.current) { corner.toPx(cornerSizeResolveSize, this).toDp() }
        }
        ?: 28.dp

/** Arbitrary non-zero size so percentage-based corners still resolve sanely. */
private val cornerSizeResolveSize = Size(1000f, 1000f)

@Preview(name = "StandardCard — dark", showBackground = true, backgroundColor = 0xFF0E0F13)
@Composable
private fun StandardCardPreview() {
    SilverBpTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            StandardCard(title = "本週概況") {
                Text(
                    text = "血壓、血糖與體重的趨勢都在目標範圍內。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StandardCard(
                title = "最近一次血壓",
                accent = MetricAccent.Bp,
                onClick = {},
            ) {
                Text(
                    text = "128 / 82 mmHg",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "點一下查看歷史",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
