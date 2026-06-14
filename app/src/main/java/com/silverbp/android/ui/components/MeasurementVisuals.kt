package com.silverbp.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppSpacing

/**
 * Stitch-aligned visual building blocks shared by the polished measurement
 * surfaces (Today's unified daily card, the per-type Insights/analytics stat
 * rows, and the weight analysis page). These are *pure visual* composables —
 * no state, no ViewModel, no measurement-domain types — so every measurement
 * track (BP / glucose / weight) can reuse the exact same look without
 * restructuring its screen.
 *
 * Stitch reference (project 7958846212031695630):
 * - "Today Dashboard" hero card: a small category dot + label, a very large
 *   hero number with a trailing unit, then a muted sub-row (pulse chip / time).
 *   → [HeroReadingValue]
 * - "Insights Analytics" stat pills: a small muted label, a big semibold value,
 *   and an optional small coloured sub-line ("High Risk"). → [StatTile]
 * - Section labels: small-caps muted headings, with an optional trailing
 *   "N today / view all" affordance. → [MeasurementSectionHeader]
 *
 * Colours come from [MaterialTheme]/[AppColors]; the only colour a caller
 * passes in is a domain category colour (BP/glucose/BMI category), which is
 * already theme-defined in the *CategoryColors helpers.
 */

/** Diameter of the category status dot rendered next to a hero reading / label. */
val MeasurementCategoryDotSize: Dp = 12.dp

/**
 * Bottom padding that baseline-aligns the trailing unit text to the tall hero
 * number (the big number's descenders sit lower than a body line). Tuned to the
 * value the Today glucose/weight sections already use.
 */
private val HeroUnitBaselinePadding: Dp = 8.dp

/**
 * A small filled circle used to surface a measurement's category colour (BP
 * stage, glucose range, BMI band). Decorative — the category label beside it
 * carries the meaning for TalkBack, so this is intentionally unlabeled.
 */
@Composable
fun CategoryDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = MeasurementCategoryDotSize,
) {
    Box(modifier = modifier.size(size).clip(CircleShape).background(color))
}

/**
 * A section heading inside the unified daily card (and any grouped measurement
 * block): a small-caps muted label, plus an optional trailing text affordance
 * (e.g. "今天 N 筆" / "查看全部") aligned to the end.
 *
 * Replaces the per-screen private `SectionHeader`/section-label copies so BP,
 * glucose and weight sections share one Stitch-polished heading. The label uses
 * the muted on-surface-variant tone Stitch uses for its section captions.
 *
 * @param title the section label (already localized).
 * @param trailingText when non-null AND [onTrailingClick] is non-null, renders a
 *   trailing [TextButton] with this text (e.g. the localized "N today" string).
 * @param onTrailingClick click handler for the trailing affordance.
 * @param trailingContentDescription optional TalkBack label for the affordance.
 */
@Composable
fun MeasurementSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    trailingContentDescription: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailingText != null && onTrailingClick != null) {
            val cd = trailingContentDescription
            TextButton(
                onClick = onTrailingClick,
                modifier = if (cd != null) {
                    Modifier.semantics { contentDescription = cd }
                } else {
                    Modifier
                },
            ) {
                Text(trailingText)
            }
        }
    }
}

/**
 * The Stitch "hero reading" block: a category dot + label on top, then a very
 * large value (the [valueContent] slot, so callers can use [HeroValueText] for a
 * single number, or [com.silverbp.android.ui.components.BpReadingValue] for the
 * SBP/DBP pair) with an optional trailing [unit], then an optional muted
 * [subContent] row (pulse chip / context / timestamp).
 *
 * Mirrors the visual currently inlined in `TodayScreen`'s BP/glucose sections so
 * the today-data track can drop weight in with the identical look, and the
 * existing two sections can adopt it without changing structure.
 *
 * Pure layout: the caller owns the click/edit affordance on the surrounding
 * container (so the whole reading stays a single TalkBack/edit target).
 *
 * @param categoryColor the domain category colour for the status dot.
 * @param categoryLabel the localized category name shown next to the dot.
 * @param unit optional trailing unit text (e.g. "mmHg", "mg/dL", "kg"); rendered
 *   muted and baseline-aligned to the bottom of the big value.
 * @param valueContent the large value slot (number / SBP-DBP pair / BMI).
 * @param subContent optional muted sub-row under the value (pulse + time, etc.).
 */
@Composable
fun HeroReadingValue(
    categoryColor: Color,
    categoryLabel: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    subContent: (@Composable () -> Unit)? = null,
    valueContent: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryDot(color = categoryColor)
            Spacer(Modifier.size(AppSpacing.itemGap))
            Text(
                categoryLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            valueContent()
            if (unit != null) {
                Text(
                    unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = HeroUnitBaselinePadding),
                )
            }
        }
        subContent?.invoke()
    }
}

/**
 * A single large hero number (the common case for glucose / weight / BMI), in the
 * Stitch "big semibold number" style. Use inside [HeroReadingValue]'s value slot;
 * BP uses [com.silverbp.android.ui.components.BpReadingValue] instead because it
 * renders the SBP/DBP pair with a slash.
 */
@Composable
fun HeroValueText(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        value,
        modifier = modifier,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
    )
}

/**
 * A Stitch analytics "stat pill": a small muted label on top, a big semibold
 * value, and an optional small coloured sub-line (e.g. a category / risk tag,
 * or a "+0.4 kg" delta). Designed to sit in a horizontal weight-1f row of three
 * (最新 / 變化 / BMI; or fasting / post-meal / lows) inside an
 * [com.silverbp.android.ui.components.StandardCard]-equivalent container.
 *
 * This *is* the card (it wraps a [StandardCard]) so a row of tiles reads as a row
 * of pills, exactly like the Stitch "Insights Analytics" header stats. Give each
 * tile `Modifier.weight(1f).fillMaxHeight()` and wrap the row in
 * `Modifier.height(IntrinsicSize.Max)` for equal heights (as the glucose stats
 * row already does).
 *
 * @param label the small muted caption (e.g. "最新", "BMI").
 * @param value the big value (already formatted, incl. unit if desired).
 * @param sub optional small sub-line under the value (delta / category text).
 * @param subColor colour for [sub]; defaults to the muted variant, pass a
 *   category colour to echo Stitch's coloured risk tag.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    subColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    StandardCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = subColor,
                maxLines = 1,
            )
        }
    }
}
