package com.silverbp.android.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.exercise.components.RecentSessionsCard
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Backs [ExerciseHistoryScreen] — the full cardio session list. Device-global:
 * exercise has NO member scoping (no memberId), so this lists EVERY session
 * regardless of the current member.
 */
class ExerciseHistoryViewModel(
    repo: ExerciseRepository = ServiceLocator.exerciseRepository,
) : ViewModel() {
    val sessions: StateFlow<List<ExerciseSession>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Full cardio exercise history — every session (newest first), grouped by month,
 * unlike the Exercise hub's History card which only previews the 3 most recent.
 * Reuses [RecentSessionsCard] for the rows and the existing exercise-detail route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseHistoryScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    vm: ExerciseHistoryViewModel = viewModel(),
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.exercise_history_all_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.exercise_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val zone = ZoneId.systemDefault()
                val monthFmt = DateTimeFormatter.ofPattern("yyyy/MM")
                // Sessions arrive newest-first; groupBy preserves encounter order,
                // so months (and rows within each) render newest-first.
                sessions.groupBy { YearMonth.from(it.startedAt.atZone(zone)) }
                    .forEach { (ym, monthSessions) ->
                        Text(
                            text = stringResource(
                                R.string.exercise_history_month_header,
                                monthFmt.format(ym),
                                monthSessions.size,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        RecentSessionsCard(
                            sessions = monthSessions,
                            onSessionClick = { onOpenDetail(it.id.toString()) },
                            titleRes = null,
                        )
                    }
            }
        }
    }
}
