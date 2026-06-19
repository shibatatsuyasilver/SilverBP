package com.silverbp.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppMotion
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.PillShape
import com.silverbp.android.ui.theme.SilverBpTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.automirrored.filled.List

/**
 * The Stitch-aligned connected "pill button group" — a single-select segmented
 * control. Used by DataHub (紀錄 / 分析) and the Confirm-screen reading tags.
 *
 * Visual contract (mirrors design/mockups/assets/app.css `.segmented`):
 * - Track = [androidx.compose.material3.ColorScheme.surfaceContainerLow] in a
 *   full [PillShape], with a small inner inset so the selected pill floats.
 * - Segments share equal width and are at least 44.dp tall (older-adult tap
 *   target); the whole row clears the 48.dp [AppSpacing.touchTarget] minimum.
 * - The SELECTED segment shows a [androidx.compose.material3.ColorScheme.primary]
 *   pill with `onPrimary` [labelLarge] SemiBold/Bold text; unselected segments
 *   are `onSurfaceVariant`. The primary pill is a single indicator that *slides*
 *   between segments on [AppMotion.springDefault], so selection reads as one
 *   continuous, senior-legible motion rather than two cross-fading blocks.
 *
 * Pure UI: holds no state. The caller owns [selectedIndex] and reacts to
 * [onSelect]. Each segment is exposed to TalkBack via [selectable] with a
 * [Role.Tab] and the group is wrapped in [selectableGroup].
 *
 * @param options the segment labels, in order (typically 2–3 short strings).
 * @param selectedIndex the index of the currently-selected segment.
 * @param onSelect invoked with a segment's index when it is tapped.
 * @param leadingIcons optional per-segment leading icons; when non-null it must
 *   be the same size as [options] (icon `i` precedes label `i`).
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcons: List<ImageVector>? = null,
) {
    if (options.isEmpty()) return
    val colors = MaterialTheme.colorScheme

    // The indicator's centre tracks the selected slot fractionally so it eases
    // (and slightly overshoots) between segments rather than snapping.
    val indicatorPos by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, options.lastIndex).toFloat(),
        animationSpec = AppMotion.springDefault(),
        label = "segmentIndicator",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(colors.surfaceContainerLow)
            .padding(5.dp)
            .selectableGroup(),
    ) {
        // Custom layout so the sliding primary pill is measured to the exact
        // width of one segment and offset by the (animated) fractional index —
        // segments stay equal-weight and the indicator can travel continuously.
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                // Slot 0: the moving selected-pill indicator.
                Box(
                    modifier = Modifier
                        .clip(PillShape)
                        .background(colors.primary),
                )
                // Slots 1..n: one labelled, tappable segment each.
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .clip(PillShape)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            )
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leadingIcons?.getOrNull(index)?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.sizeIn(maxWidth = 18.dp, maxHeight = 18.dp),
                                tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            // Selected reads a touch heavier (mockup uses 700).
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val count = options.size
            val gap = 4.dp.roundToPx()
            val totalGap = gap * (count - 1)
            val segWidth = ((constraints.maxWidth - totalGap) / count).coerceAtLeast(0)

            // Measure segments at their equal share; pick the tallest for height.
            val segConstraints = constraints.copy(
                minWidth = segWidth,
                maxWidth = segWidth,
                minHeight = 0,
            )
            val segPlaceables = measurables.drop(1).map { it.measure(segConstraints) }
            val rowHeight = (segPlaceables.maxOfOrNull { it.height } ?: 0)
                .coerceAtLeast(44.dp.roundToPx())

            // The indicator fills exactly one segment slot.
            val indicator = measurables.first().measure(
                constraints.copy(
                    minWidth = segWidth,
                    maxWidth = segWidth,
                    minHeight = rowHeight,
                    maxHeight = rowHeight,
                ),
            )

            layout(constraints.maxWidth, rowHeight) {
                // Indicator first (underneath), positioned by the animated index.
                val indicatorX = (indicatorPos * (segWidth + gap)).toInt()
                indicator.placeRelative(x = indicatorX, y = 0)
                // Segments on top, evenly distributed.
                segPlaceables.forEachIndexed { index, placeable ->
                    val x = index * (segWidth + gap)
                    val y = (rowHeight - placeable.height) / 2
                    placeable.placeRelative(x = x, y = y)
                }
            }
        }
    }
}

@Preview(name = "SegmentedControl · Dark", showBackground = true, backgroundColor = 0xFF0E0F13)
@Composable
private fun SegmentedControlPreview() {
    SilverBpTheme {
        Box(modifier = Modifier.padding(20.dp)) {
            SegmentedControl(
                options = listOf("紀錄", "分析"),
                selectedIndex = 1,
                onSelect = {},
                leadingIcons = listOf(
                    Icons.AutoMirrored.Filled.List,
                    Icons.Filled.BarChart,
                ),
            )
        }
    }
}
