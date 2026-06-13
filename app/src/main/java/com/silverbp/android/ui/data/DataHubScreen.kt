package com.silverbp.android.ui.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.silverbp.android.ui.history.GlucoseHistoryFilterAction
import com.silverbp.android.ui.history.GlucoseHistoryScreen
import com.silverbp.android.ui.history.GlucoseHistoryViewModel
import com.silverbp.android.ui.history.HistoryFilterAction
import com.silverbp.android.ui.history.HistoryScreen
import com.silverbp.android.ui.history.HistoryViewModel
import com.silverbp.android.ui.insights.GlucoseInsightsScreen
import com.silverbp.android.ui.insights.InsightsScreen
import com.silverbp.android.ui.member.MemberSwitcherChip
import com.silverbp.android.ui.theme.AppSpacing

/** Segments of the Data (數據) tab, mirroring iOS DataHubView's [紀錄][分析] picker. */
private enum class DataSection(val labelRes: Int) {
    Records(R.string.tab_history),   // 紀錄 — reading list
    Insights(R.string.tab_insights), // 分析 — charts
}

/** Measurement type the Data tab is showing — BP (default) or glucose (v19). */
private enum class MeasureType(val labelRes: Int) {
    Bp(R.string.measure_bp),
    Glucose(R.string.measure_glucose),
}

/**
 * The "數據" (Data) tab — mirrors iOS `DataHubView`. A 血壓|血糖 measurement-type
 * filter-chip row (v19) sits above the 紀錄|分析 segmented control. The segmented
 * control toggles between the history list (紀錄, default) and the analytics
 * charts (分析); the measurement-type chips pick whether those show blood pressure
 * (unchanged) or blood glucose. The member switcher scopes both. History stays
 * prominently reachable here instead of being buried under Coach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataHubScreen(
    onEditReading: (String) -> Unit,
    onOpenReport: () -> Unit,
    // Default no-op so AppNavHost compiles unchanged until it wires the
    // MEMBER_MANAGE navigation (the chip self-hides for single-member installs).
    onManageMembers: () -> Unit = {},
    // Default no-op so AppNavHost compiles unchanged until the capture/confirm
    // track wires the glucose confirm-edit route.
    onEditGlucose: (String) -> Unit = {},
) {
    // Single HistoryViewModel shared between the list and the TopAppBar filter
    // action so changing range/sort in the app bar drives the same list. Same for
    // glucose. Both are hoisted here (cheap when not displayed — they only collect
    // while their screen is composed via WhileSubscribed).
    val historyVm: HistoryViewModel = viewModel()
    val glucoseHistoryVm: GlucoseHistoryViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    var section by remember { mutableIntStateOf(DataSection.Records.ordinal) }
    var measure by remember { mutableIntStateOf(MeasureType.Bp.ordinal) }
    val active = DataSection.entries[section]
    val activeMeasure = MeasureType.entries[measure]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_data), fontWeight = FontWeight.SemiBold) },
                actions = {
                    MemberSwitcherChip(onManageMembers = onManageMembers)
                    if (active == DataSection.Records) {
                        when (activeMeasure) {
                            MeasureType.Bp -> HistoryFilterAction(historyVm)
                            MeasureType.Glucose -> GlucoseHistoryFilterAction(glucoseHistoryVm)
                        }
                    }
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
            // 血壓 | 血糖 measurement-type switch (above the 紀錄|分析 control).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.itemGap),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                MeasureType.entries.forEachIndexed { idx, m ->
                    FilterChip(
                        selected = measure == idx,
                        onClick = { measure = idx },
                        label = { Text(stringResource(m.labelRes)) },
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.tight),
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

            when (activeMeasure) {
                MeasureType.Bp -> when (active) {
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
                MeasureType.Glucose -> when (active) {
                    DataSection.Records -> GlucoseHistoryScreen(
                        onEdit = onEditGlucose,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier.weight(1f),
                        vm = glucoseHistoryVm,
                    )
                    DataSection.Insights -> GlucoseInsightsScreen(
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
