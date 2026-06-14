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
import com.silverbp.android.ui.history.UnifiedHistoryFilterAction
import com.silverbp.android.ui.history.UnifiedHistoryScreen
import com.silverbp.android.ui.history.UnifiedHistoryViewModel
import com.silverbp.android.ui.insights.GlucoseInsightsScreen
import com.silverbp.android.ui.insights.InsightsScreen
import com.silverbp.android.ui.insights.WeightInsightsScreen
import com.silverbp.android.ui.member.MemberSwitcherChip
import com.silverbp.android.ui.theme.AppSpacing

/** Segments of the Data (數據) tab, mirroring iOS DataHubView's [紀錄][分析] picker. */
private enum class DataSection(val labelRes: Int) {
    Records(R.string.tab_history),   // 紀錄 — unified reading list (both BP + glucose)
    Insights(R.string.tab_insights), // 分析 — charts (per-type; charts can't merge)
}

/**
 * Measurement type for the 分析 (Insights) segment only. The 紀錄 list is unified
 * across both types (owner decision §4), so this switch lives inside 分析 — where
 * the BP and glucose charts genuinely can't merge — rather than at the top level.
 */
private enum class MeasureType(val labelRes: Int) {
    Bp(R.string.measure_bp),
    Glucose(R.string.measure_glucose),
    Weight(R.string.measure_weight),
}

/**
 * The "數據" (Data) tab — mirrors iOS `DataHubView`. A 紀錄|分析 segmented control
 * toggles between the unified history list (紀錄, default — one day shows BOTH blood
 * pressure and blood glucose) and the analytics charts (分析). Because the BP and
 * glucose charts can't be merged, the 血壓|血糖 measurement-type switch is shown only
 * inside the 分析 segment, picking between the BP [InsightsScreen] (incl. the
 * multi-member compare mode, unchanged) and the [GlucoseInsightsScreen]. The member
 * switcher scopes both segments. History stays prominently reachable here.
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
    // Single UnifiedHistoryViewModel shared between the list and the TopAppBar
    // filter action so changing range/sort in the app bar drives the same list.
    // Hoisted here (cheap when not displayed — it only collects while its screen
    // is composed via WhileSubscribed).
    val unifiedHistoryVm: UnifiedHistoryViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    var section by remember { mutableIntStateOf(DataSection.Records.ordinal) }
    // Insights-only measurement type; default BP (compare mode lives on the BP side).
    var insightsMeasure by remember { mutableIntStateOf(MeasureType.Bp.ordinal) }
    val active = DataSection.entries[section]
    val activeInsightsMeasure = MeasureType.entries[insightsMeasure]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_data), fontWeight = FontWeight.SemiBold) },
                actions = {
                    MemberSwitcherChip(onManageMembers = onManageMembers)
                    // The unified list's range/sort filter; only in the 紀錄 segment.
                    if (active == DataSection.Records) {
                        UnifiedHistoryFilterAction(unifiedHistoryVm)
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

            // 血壓 | 血糖 type switch — only meaningful for 分析 (charts can't merge).
            if (active == DataSection.Insights) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.itemGap),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
                ) {
                    MeasureType.entries.forEachIndexed { idx, m ->
                        FilterChip(
                            selected = insightsMeasure == idx,
                            onClick = { insightsMeasure = idx },
                            label = { Text(stringResource(m.labelRes)) },
                        )
                    }
                }
            }

            when (active) {
                DataSection.Records -> UnifiedHistoryScreen(
                    onEditBp = onEditReading,
                    onEditGlucose = onEditGlucose,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.weight(1f),
                    vm = unifiedHistoryVm,
                )
                DataSection.Insights -> when (activeInsightsMeasure) {
                    MeasureType.Bp -> InsightsScreen(
                        onOpenReport = onOpenReport,
                        modifier = Modifier.weight(1f),
                    )
                    MeasureType.Glucose -> GlucoseInsightsScreen(
                        modifier = Modifier.weight(1f),
                    )
                    MeasureType.Weight -> WeightInsightsScreen(
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
