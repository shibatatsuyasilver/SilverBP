package com.silverbp.android.ui.data

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.ui.history.HistoryFilterAction
import com.silverbp.android.ui.history.HistoryScreen
import com.silverbp.android.ui.history.HistoryViewModel
import com.silverbp.android.ui.insights.InsightsScreen
import com.silverbp.android.ui.theme.AppSpacing

/** Segments of the Data (數據) tab, mirroring iOS DataHubView's [紀錄][分析] picker. */
private enum class DataSection(val labelRes: Int) {
    Records(R.string.tab_history),   // 紀錄 — BP reading list
    Insights(R.string.tab_insights), // 分析 — charts
}

/**
 * The "數據" (Data) tab — mirrors iOS `DataHubView`. A segmented control toggles
 * between the BP history list (紀錄, default) and the analytics charts (分析).
 * History stays prominently reachable here instead of being buried under Coach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataHubScreen(
    onEditReading: (String) -> Unit,
    onOpenReport: () -> Unit,
) {
    // Single HistoryViewModel shared between the list and the TopAppBar filter
    // action so changing range/sort in the app bar drives the same list.
    val historyVm: HistoryViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    var section by remember { mutableIntStateOf(DataSection.Records.ordinal) }
    val active = DataSection.entries[section]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_data), fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (active == DataSection.Records) HistoryFilterAction(historyVm)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.itemGap),
            ) {
                DataSection.entries.forEachIndexed { idx, s ->
                    SegmentedButton(
                        selected = section == idx,
                        onClick = { section = idx },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = DataSection.entries.size),
                    ) {
                        Text(stringResource(s.labelRes))
                    }
                }
            }

            when (active) {
                DataSection.Records -> HistoryScreen(
                    onEdit = onEditReading,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.weight(1f),
                    vm = historyVm,
                )
                DataSection.Insights -> InsightsScreen(
                    onOpenReport = onOpenReport,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
