package com.silverbp.android.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A generic vertical scroll ("wheel") picker. The centred row is the selection;
 * a subtle highlight band marks it. Built on a [LazyColumn] + snap fling so the
 * nearest item always lands in the centre, mirroring the iOS/Android picker
 * idiom without pulling in a third-party wheel library.
 *
 * @param items the values to choose from (any type).
 * @param selectedIndex the currently selected index into [items].
 * @param onSelectedIndexChange called with the settled centred index after each
 *   scroll/step. The caller owns the state.
 * @param label maps an item to its display string (large, legible).
 * @param contentDescription spoken name of the whole control for TalkBack.
 * @param visibleRows odd count of rows shown (default 5); the middle one is the
 *   selection. Drives the control height.
 *
 * a11y approach: the [LazyColumn] is not individually focusable per row (rows
 * would be a flick-list trap on a wheel). Instead the control is merged into a
 * single node via [clearAndSetSemantics] that carries [contentDescription] +
 * [stateDescription] = the selected value, and we surface explicit up/down
 * stepper [IconButton]s (each labelled) so a TalkBack user can change the value
 * one step at a time without flinging. Sighted users keep the natural scroll.
 */
@Composable
fun <T> WheelPicker(
    items: List<T>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    label: (T) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    visibleRows: Int = 5,
) {
    if (items.isEmpty()) return
    val rowHeight = 48.dp
    val rows = if (visibleRows % 2 == 0) visibleRows + 1 else visibleRows
    val half = rows / 2

    val safeSelected = selectedIndex.coerceIn(0, items.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeSelected)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // The centred item = first visible item (we pad the top by [half] blanks so
    // index 0 can sit in the centre). firstVisibleItemIndex is the settled value
    // once snapped; we nudge it by the scroll offset to pick the nearest row.
    val centeredIndex by remember {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            val itemPx = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1
            val adjusted = if (offset > itemPx / 2) first + 1 else first
            adjusted.coerceIn(0, items.lastIndex)
        }
    }

    // Report the settled index up. distinctUntilChanged so we only fire on change.
    // selectedIndex is read through rememberUpdatedState: the collector is launched
    // once (its key, items.size, stays constant while the wheel is open), so a plain
    // capture would freeze selectedIndex at its first value. Then scrolling away and
    // back to the opening value would compare equal-to-stale and be dropped — Done
    // would commit the overshoot, not the value the user sees. The updated state
    // keeps the guard comparing against the CURRENT selection.
    val currentSelected = rememberUpdatedState(selectedIndex)
    LaunchedEffect(items.size) {
        snapshotFlow { centeredIndex }
            .distinctUntilChanged()
            .collect { idx -> if (idx != currentSelected.value) onSelectedIndexChange(idx) }
    }

    // Keep the wheel aligned when the caller changes selection (e.g. unit switch,
    // stepper, or initial value) without fighting an in-flight user scroll. Snaps
    // INSTANTLY rather than animating: an animated scroll emits intermediate centred
    // indices that the report-back above would commit as the selection, and a second
    // stepper tap arriving mid-animation compounds them into a large overshoot
    // (e.g. one down-tap jumping 27 years). An instant jump has no intermediate
    // frames, so each stepper tap moves exactly one row and never overshoots.
    LaunchedEffect(safeSelected) {
        if (!listState.isScrollInProgress &&
            listState.firstVisibleItemIndex != safeSelected
        ) {
            listState.scrollToItem(safeSelected)
        }
    }

    val selectedLabel = label(items[safeSelected])
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Decrement / increment steppers — the accessible (and tremor-friendly)
        // way to change the value by exactly one.
        IconButton(
            onClick = { if (safeSelected > 0) onSelectedIndexChange(safeSelected - 1) },
            enabled = safeSelected > 0,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.picker_previous),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(rowHeight * rows)
                // Merge ONLY the scrollable wheel into a single read-only node that
                // announces the control name + current value. The stepper IconButtons
                // sit outside this cleared subtree, so their labels + click actions
                // survive for TalkBack — clearing on the parent Row would wipe them
                // and leave the wheel unchangeable for assistive tech.
                .clearAndSetSemantics {
                    this.contentDescription = contentDescription
                    this.stateDescription = selectedLabel
                },
            contentAlignment = Alignment.Center,
        ) {
            // Centre highlight band behind the selected row.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                content = {},
            )
            HorizontalDivider(Modifier.padding(top = rowHeight / 2 + 1.dp))
            HorizontalDivider(Modifier.padding(bottom = rowHeight / 2 + 1.dp))

            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = rowHeight * half),
                modifier = Modifier.fillMaxWidth().heightIn(max = rowHeight * rows),
            ) {
                itemsIndexed(items) { index, item ->
                    val isSelected = index == centeredIndex
                    Box(
                        modifier = Modifier.fillMaxWidth().height(rowHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label(item),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            },
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = {
                if (safeSelected < items.lastIndex) onSelectedIndexChange(safeSelected + 1)
            },
            enabled = safeSelected < items.lastIndex,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.picker_next),
            )
        }
    }
}
