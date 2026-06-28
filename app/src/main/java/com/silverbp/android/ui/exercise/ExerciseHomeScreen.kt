package com.silverbp.android.ui.exercise

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rowing
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.exercise.RunState
import com.silverbp.android.settings.UserSettings
import com.silverbp.android.strength.StrengthWorkoutSession
import com.silverbp.android.ui.achievements.MedalUnlockBannerHost
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.SegmentedControl
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.exercise.components.ExerciseTrendsCard
import com.silverbp.android.ui.exercise.components.MedalShowcaseCard
import com.silverbp.android.ui.exercise.components.RecentSessionsCard
import com.silverbp.android.ui.exercise.components.TodayStepsCard
import com.silverbp.android.ui.theme.AppMotion
import com.silverbp.android.ui.theme.AppSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sub-sections of the training hub hosted inside the Exercise tab. */
private enum class HubSection(val labelRes: Int, val icon: ImageVector) {
    Plan(R.string.hub_section_plan, Icons.Filled.CalendarMonth),
    Library(R.string.hub_section_library, Icons.AutoMirrored.Filled.List),
    History(R.string.hub_section_history, Icons.Filled.History),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseHomeScreen(
    onStartSession: () -> Unit,
    onStartStrengthSession: () -> Unit,
    onCaptureMachine: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenMedals: () -> Unit,
    onOpenSummary: () -> Unit,
    onMeasureBp: () -> Unit = {},
    vm: ExerciseHomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var section by remember { mutableIntStateOf(0) }

    val recoverable by vm.recoverable.collectAsStateWithLifecycle()
    // 還原檢查點同樣需要精確位置權限,否則前景服務在 Android 14+ 會崩潰。
    // 重用 Start 流程的權限請求,缺少時請求後再還原;使用者拒絕則保留檢查點。
    val (recoverPerm, requestRecoverPerm) = rememberExercisePermissionState()

    LifecycleResumeEffect(Unit) {
        ServiceLocator.achievementStore.launchRefresh()
        vm.refreshWeekSteps()
        vm.checkRecoverable()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.tab_exercise)) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (recoverable != null) {
                    // Finished 檢查點:運動已停止、只差摘要頁儲存就被殺 — 不需重新
                    // 追蹤,因此不請求位置權限,直接還原並導向摘要頁儲存或捨棄。
                    val finished = recoverable!!.runState == RunState.Finished
                    Box(Modifier.padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.itemGap)) {
                        RecoverSessionCard(
                            // 缺精確位置權限時先請求;授權後才還原並開啟運動畫面,
                            // 拒絕則 onReady 不觸發,檢查點原封不動留待重試。
                            locationDenied = !finished && recoverPerm.locationDenied,
                            onResume = {
                                if (finished) {
                                    vm.resumeRecoverable()
                                    onOpenSummary()
                                } else {
                                    requestRecoverPerm {
                                        vm.resumeRecoverable()
                                        onStartSession()
                                    }
                                }
                            },
                            onDiscard = vm::discardRecoverable,
                            onOpenSettings = recoverPerm::openAppSettings,
                        )
                    }
                }

                SegmentedControl(
                    options = HubSection.entries.map { stringResource(it.labelRes) },
                    selectedIndex = section,
                    onSelect = { section = it },
                    leadingIcons = HubSection.entries.map { it.icon },
                    modifier = Modifier.padding(
                        horizontal = AppSpacing.screenH,
                        vertical = AppSpacing.itemGap,
                    ),
                )

                when (HubSection.entries[section]) {
                    HubSection.Plan -> PlanSection(
                        state = state,
                        vm = vm,
                        onStartSession = onStartSession,
                        onCaptureMachine = onCaptureMachine,
                        onOpenMedals = onOpenMedals,
                    )
                    HubSection.Library -> StrengthLibrarySection(
                        onStartStrengthSession = onStartStrengthSession,
                    )
                    HubSection.History -> HistorySection(
                        state = state,
                        onOpenDetail = onOpenDetail,
                        onOpenHistory = onOpenHistory,
                    )
                }
            }

            MedalUnlockBannerHost(
                onTap = onOpenMedals,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun RecoverSessionCard(
    locationDenied: Boolean,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    StandardCard(title = stringResource(R.string.exercise_recover_title)) {
        Text(
            stringResource(R.string.exercise_recover_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        // 使用者已拒絕位置權限:無法還原 GPS 紀錄,引導至系統設定。
        if (locationDenied) {
            Text(
                stringResource(R.string.exercise_recover_location_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            ExpressiveSecondaryButton(
                text = stringResource(R.string.exercise_discard),
                onClick = onDiscard,
                modifier = Modifier.weight(1f),
            )
            if (locationDenied) {
                ExpressivePrimaryButton(
                    text = stringResource(R.string.exercise_settings_open_app_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ExpressivePrimaryButton(
                    text = stringResource(R.string.exercise_resume),
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PlanSection(
    state: ExerciseHomeUiState,
    vm: ExerciseHomeViewModel,
    onStartSession: () -> Unit,
    onCaptureMachine: () -> Unit,
    onOpenMedals: () -> Unit,
) {
    val (perm, requestPerm) = rememberExercisePermissionState()
    val achievementState by ServiceLocator.achievementStore.state.collectAsStateWithLifecycle()
    val settings by ServiceLocator.userSettings.flow
        .collectAsStateWithLifecycle(initialValue = UserSettings())
    var selectedKind by remember { mutableStateOf(ActivityKind.Walking) }

    fun startCardio(kind: ActivityKind) {
        requestPerm {
            ServiceLocator.exerciseController.start(kind)
            onStartSession()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        // Pick a cardio kind (2×2 tiles), then start. Machine kinds are logged via
        // the camera card below; strength is started from the 動作庫 tab.
        StandardCard(title = stringResource(R.string.hub_start_exercise)) {
            CardioKindGrid(selected = selectedKind, onSelect = { selectedKind = it })
            ExpressivePrimaryButton(
                text = stringResource(R.string.hub_start_exercise),
                icon = Icons.Filled.PlayArrow,
                fillWidth = true,
                onClick = {
                    startCardio(selectedKind)
                },
            )
        }

        // Log a finished gym-machine workout by photographing its console (OCR).
        MachineCaptureCard(onClick = onCaptureMachine)

        TodayTaskCard(state = state)

        if (perm.locationDenied || !perm.hasFineLocation) {
            PermissionHint(
                denied = perm.locationDenied,
                onOpenSettings = perm::openAppSettings,
            )
        }

        TodayStepsCard(
            todaySteps = achievementState.stats.todaySteps,
            dailyGoal = settings.dailyStepGoal,
        )

        MedalShowcaseCard(
            state = achievementState,
            onViewAll = onOpenMedals,
        )

        ExerciseTrendsCard(
            state = state,
            onSelectRange = vm::setRange,
        )

        Spacer(Modifier.height(AppSpacing.itemGap))
    }
}

@Composable
private fun TodayTaskCard(state: ExerciseHomeUiState) {
    StandardCard(title = stringResource(R.string.hub_today_task_title)) {
        val task = state.todayExerciseTask
        when {
            task != null -> {
                Text(task.title, style = MaterialTheme.typography.bodyLarge)
                task.targetValue?.let { v ->
                    Text(
                        stringResource(R.string.hub_today_task_minutes, v.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.hasPlan -> Text(
                stringResource(R.string.hub_today_task_rest),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                stringResource(R.string.hub_today_task_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Entry card for logging a gym-machine workout from a console photo (OCR). */
@Composable
private fun MachineCaptureCard(onClick: () -> Unit) {
    StandardCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.machine_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.machine_card_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 2×2 grid of GPS-trackable cardio kinds; the selected tile is filled with its
 *  activity-identity colour ([colorForKind]). */
@Composable
private fun CardioKindGrid(selected: ActivityKind, onSelect: (ActivityKind) -> Unit) {
    // Explicit order to match the design: 步行 / 健走 / 跑步 / 腳踏車.
    val kinds = listOf(
        ActivityKind.Walking,
        ActivityKind.BriskWalking,
        ActivityKind.Running,
        ActivityKind.Cycling,
    )
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
        kinds.chunked(2).forEach { rowKinds ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                rowKinds.forEach { kind ->
                    CardioKindTile(
                        kind = kind,
                        selected = selected == kind,
                        onClick = { onSelect(kind) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardioKindTile(
    kind: ActivityKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Activity identity colour (NOT a metric accent): the selected tile fills with
    // the kind's colour; unselected tiles tint the icon with it on a surface card.
    val kindColor = colorForKind(kind)
    val container by animateColorAsState(
        targetValue = if (selected) kindColor else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = AppMotion.springDefault(),
        label = "cardioTileContainer",
    )
    val iconTint = if (selected) Color.White else kindColor
    val textColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        modifier = modifier.height(88.dp),
        shape = RoundedCornerShape(AppSpacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(iconForKind(kind), null, tint = iconTint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(AppSpacing.tight))
            Text(
                stringResource(labelResForKind(kind)),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HistorySection(
    state: ExerciseHomeUiState,
    onOpenDetail: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Text(
            stringResource(R.string.hub_history_cardio),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        RecentSessionsCard(
            sessions = state.recent,
            onSessionClick = { onOpenDetail(it.id.toString()) },
        )
        if (state.allCount > state.recent.size) {
            TextButton(
                onClick = onOpenHistory,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.exercise_history_view_all, state.allCount))
            }
        }

        Text(
            stringResource(R.string.hub_history_strength),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        StrengthHistoryCard(sessions = state.strengthSessions)

        Spacer(Modifier.height(AppSpacing.itemGap))
    }
}

@Composable
private fun StrengthHistoryCard(sessions: List<StrengthWorkoutSession>) {
    StandardCard(verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)) {
        if (sessions.isEmpty()) {
            Text(
                stringResource(R.string.hub_history_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            sessions.forEach { s -> StrengthSessionRow(s) }
        }
    }
}

@Composable
private fun StrengthSessionRow(session: StrengthWorkoutSession) {
    val fmt = DateTimeFormatter
        .ofPattern("MM/dd HH:mm", Locale.TAIWAN)
        .withZone(ZoneId.systemDefault())
    val durationMs = (session.endedAt - session.startedAt).coerceAtLeast(0L)
    Column {
        Text(
            fmt.format(Instant.ofEpochMilli(session.startedAt)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            ExerciseMath.formatDuration(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun iconForKind(kind: ActivityKind): ImageVector = when (kind) {
    ActivityKind.Walking -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityKind.Running -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityKind.BriskWalking -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityKind.Cycling -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityKind.Treadmill -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityKind.IndoorBike -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityKind.Elliptical -> Icons.Filled.FitnessCenter
    ActivityKind.Rower -> Icons.Filled.Rowing
    ActivityKind.StairClimber -> Icons.Filled.Stairs
}

private fun labelResForKind(kind: ActivityKind): Int = when (kind) {
    ActivityKind.Walking -> R.string.exercise_kind_walking
    ActivityKind.Running -> R.string.exercise_kind_running
    ActivityKind.BriskWalking -> R.string.exercise_kind_brisk_walking
    ActivityKind.Cycling -> R.string.exercise_kind_cycling
    ActivityKind.Treadmill -> R.string.exercise_kind_treadmill
    ActivityKind.IndoorBike -> R.string.exercise_kind_indoor_bike
    ActivityKind.Elliptical -> R.string.exercise_kind_elliptical
    ActivityKind.Rower -> R.string.exercise_kind_rower
    ActivityKind.StairClimber -> R.string.exercise_kind_stair_climber
}

@Composable
private fun PermissionHint(denied: Boolean, onOpenSettings: () -> Unit) {
    StandardCard(title = stringResource(R.string.exercise_location_permission_title)) {
        Text(
            if (denied) stringResource(R.string.exercise_location_denied)
            else stringResource(R.string.exercise_location_permission_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (denied) {
            ExpressiveSecondaryButton(
                text = stringResource(R.string.exercise_settings_open_app_settings),
                onClick = onOpenSettings,
            )
        }
    }
}
