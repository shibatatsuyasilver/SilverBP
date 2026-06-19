package com.silverbp.android.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressiveAssistChip
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.exercise.colorForModule
import com.silverbp.android.ui.theme.AppSpacing
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachWeeklyPlanScreen(
    onClose: () -> Unit,
    vm: CoachViewModel = viewModel(),
) {
    val plan by vm.weeklyPlan.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.coach_weekly_plan_title),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val p = plan
        if (p == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            p.days.forEach { day ->
                DayCard(
                    day = day,
                    isToday = p.todayIndex == day.dayOffset,
                    onMove = { taskId, target -> vm.moveTask(taskId, target) },
                    onSkip = { taskId, skipped -> vm.setSkipped(taskId, skipped) },
                )
            }
        }
    }
}

@Composable
private fun DayCard(
    day: WeekDayUi,
    isToday: Boolean,
    onMove: (taskId: String, newDayOffset: Int?) -> Unit,
    onSkip: (taskId: String, skipped: Boolean) -> Unit,
) {
    // Today's card sits on the (opaque, theme-defined) surfaceVariant so it reads
    // as the active day without changing any layout or behaviour. Other days use
    // the StandardCard default (surfaceContainer).
    StandardCard(
        containerColor = if (isToday) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dayLabel(day.dayOffset),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isToday) {
                Spacer(Modifier.size(AppSpacing.itemGap))
                ExpressiveAssistChip(
                    label = stringResource(R.string.coach_weekly_plan_today),
                    onClick = {},
                )
            }
        }
        if (day.tasks.isEmpty()) {
            Text(
                stringResource(R.string.coach_weekly_plan_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            day.tasks.forEachIndexed { index, task ->
                if (index > 0) HorizontalDivider()
                TaskRow(
                    task = task,
                    currentDayOffset = day.dayOffset,
                    onMove = onMove,
                    onSkip = onSkip,
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: WeekTaskUi,
    currentDayOffset: Int,
    onMove: (taskId: String, newDayOffset: Int?) -> Unit,
    onSkip: (taskId: String, skipped: Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var moveExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (task.completed && !task.skipped) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(AppSpacing.itemGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.skipped) TextDecoration.LineThrough else null,
                color = if (task.skipped) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            // Module label with its lifestyle-module identity dot (NOT MetricAccent —
            // these are coach modules, tinted by colorForModule on the module key).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(colorForModule(task.moduleKey)),
                )
                Spacer(Modifier.size(AppSpacing.tight + 2.dp))
                Text(
                    stringResource(moduleLabelRes(task.moduleKey)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.skipped) {
                Text(
                    stringResource(R.string.coach_weekly_plan_skipped),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.coach_task_action_move)) },
                    onClick = {
                        menuExpanded = false
                        moveExpanded = true
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (task.skipped) R.string.coach_task_action_unskip
                                else R.string.coach_task_action_skip,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onSkip(task.id, !task.skipped)
                    },
                )
            }
            // Day picker for "move to another day": list Mon..Sun + a reset
            // entry that clears the override (movedDayOffset = null).
            DropdownMenu(expanded = moveExpanded, onDismissRequest = { moveExpanded = false }) {
                (0..6).forEach { offset ->
                    DropdownMenuItem(
                        text = { Text(dayLabel(offset)) },
                        enabled = offset != currentDayOffset,
                        onClick = {
                            moveExpanded = false
                            onMove(task.id, offset)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.coach_task_move_reset)) },
                    onClick = {
                        moveExpanded = false
                        onMove(task.id, null)
                    },
                )
            }
        }
    }
}

/** Mon..Sun localized short name; dayOffset 0 = Monday. */
private fun dayLabel(dayOffset: Int): String =
    DayOfWeek.of(dayOffset.coerceIn(0, 6) + 1)
        .getDisplayName(TextStyle.FULL, Locale.getDefault())

private fun moduleLabelRes(key: ModuleKey): Int = when (key) {
    ModuleKey.Exercise -> R.string.coach_module_exercise
    ModuleKey.Diet -> R.string.coach_module_diet
    ModuleKey.Sleep -> R.string.coach_module_sleep
    ModuleKey.Medication -> R.string.coach_module_medication
}
