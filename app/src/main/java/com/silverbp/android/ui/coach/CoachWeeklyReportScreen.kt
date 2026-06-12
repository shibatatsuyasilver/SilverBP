package com.silverbp.android.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.coach.WeeklyReport
import com.silverbp.android.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachWeeklyReportScreen(
    onClose: () -> Unit,
    vm: CoachWeeklyReportViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.coach_weekly_report_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.regenerate() }, enabled = !state.streaming) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.report?.let { report ->
                ReportSummaryCard(report)
            }
            SectionCard(stringResource(R.string.coach_narration_title)) {
                if (state.streaming && state.narration.isEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    if (state.streaming) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        state.narration.ifBlank {
                            stringResource(R.string.coach_narration_placeholder)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                state.error?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportSummaryCard(r: WeeklyReport) {
    SectionCard(stringResource(R.string.coach_weekly_progress_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ReportLine("SBP", "${"%.1f".format(r.sbpMean)} mmHg (${"%+.1f".format(r.sbpDelta)})")
            ReportLine(stringResource(R.string.coach_module_exercise), "${r.aerobicMin} / ${r.aerobicTarget} min")
            ReportLine(stringResource(R.string.coach_module_sleep), "${"%.1f".format(r.sleepMeanH)} h")
            ReportLine(stringResource(R.string.coach_module_diet), stringResource(R.string.coach_sodium_days_over, r.sodiumDaysOver))
            ReportLine(stringResource(R.string.coach_module_medication), "${(r.medAdherence * 100).toInt()}%")
        }
    }
}

@Composable
private fun ReportLine(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}
