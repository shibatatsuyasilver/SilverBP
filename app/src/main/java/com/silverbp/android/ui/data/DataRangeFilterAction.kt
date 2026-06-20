package com.silverbp.android.ui.data

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.silverbp.android.R
import com.silverbp.android.ui.history.DateRange
import com.silverbp.android.ui.history.DateRangeMenuItems

/**
 * Range-only date-filter funnel for the 分析 (Insights) segment of the Data hub.
 *
 * Mirrors the 紀錄 funnel ([com.silverbp.android.ui.history.UnifiedHistoryFilterAction])
 * — same [Icons.Filled.FilterList] icon and the same dropdown range options (via the
 * shared [DateRangeMenuItems]) — but omits the sort section, since charts have no
 * sort concept. [onRange] writes through to the shared
 * [com.silverbp.android.ui.history.DataRangeFilterStore], so a range picked here
 * applies to 紀錄 and all three 分析 charts at once ("連動共用").
 */
@Composable
fun DataRangeFilterAction(
    currentRange: DateRange,
    onRange: (DateRange) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.a11y_filter_readings),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DateRangeMenuItems(
                currentRange = currentRange,
                onRange = onRange,
                onDismiss = { expanded = false },
            )
        }
    }
}
