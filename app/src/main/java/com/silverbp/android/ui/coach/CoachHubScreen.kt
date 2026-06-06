package com.silverbp.android.ui.coach

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.history.HistoryFilterAction
import com.silverbp.android.ui.history.HistoryScreen
import com.silverbp.android.ui.history.HistoryViewModel

/** Sub-sections hosted inside the merged Coach tab. */
private enum class CoachHubSection(val labelRes: Int) {
    Coach(R.string.tab_coach),
    History(R.string.tab_history),
}

/**
 * The Coach tab now also hosts the BP history list (the former standalone
 * 紀錄 tab) under a [SecondaryTabRow], mirroring the Exercise training hub.
 *
 * Gating: the merged tab is ALWAYS visible because it holds the core history
 * list. Only the Coach sub-section respects [com.silverbp.android.settings.
 * UserSettings.enableCoach] — when Coach is off the hub collapses to a single
 * History view with no sub-tab row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachHubScreen(
    onEditReading: (String) -> Unit,
    onOpenWeeklyReport: () -> Unit = {},
    onOpenWeeklyPlan: () -> Unit = {},
    onOpenLogDiet: () -> Unit = {},
    onOpenLogSleep: () -> Unit = {},
    onOpenLogMedication: () -> Unit = {},
    onStartExercise: () -> Unit = {},
) {
    val settings by ServiceLocator.userSettings.flow.collectAsStateWithLifecycle(initialValue = null)
    // settings == null on cold start: assume Coach on (matches its default-true)
    // so we don't flash a History-only layout before settings load.
    val coachEnabled = settings?.enableCoach ?: true

    // Single HistoryViewModel shared between the list and the TopAppBar filter
    // action, so changing the range/sort in the app bar drives the same list.
    val historyVm: HistoryViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }

    var section by remember { mutableIntStateOf(CoachHubSection.Coach.ordinal) }
    val activeSection = if (coachEnabled) CoachHubSection.entries[section] else CoachHubSection.History

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(activeSection.labelRes),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    if (activeSection == CoachHubSection.History) {
                        HistoryFilterAction(historyVm)
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
            if (coachEnabled) {
                SecondaryTabRow(selectedTabIndex = section) {
                    CoachHubSection.entries.forEachIndexed { idx, s ->
                        Tab(
                            selected = section == idx,
                            onClick = { section = idx },
                            text = { Text(stringResource(s.labelRes)) },
                        )
                    }
                }
            }

            when (activeSection) {
                CoachHubSection.Coach -> CoachScreen(
                    onOpenWeeklyReport = onOpenWeeklyReport,
                    onOpenWeeklyPlan = onOpenWeeklyPlan,
                    onOpenLogDiet = onOpenLogDiet,
                    onOpenLogSleep = onOpenLogSleep,
                    onOpenLogMedication = onOpenLogMedication,
                    onStartExercise = onStartExercise,
                    modifier = Modifier.weight(1f),
                )
                CoachHubSection.History -> HistoryScreen(
                    onEdit = onEditReading,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.weight(1f),
                    vm = historyVm,
                )
            }
        }
    }
}
