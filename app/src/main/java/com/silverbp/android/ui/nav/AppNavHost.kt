package com.silverbp.android.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.legal.CURRENT_PRIVACY_POLICY_VERSION
import com.silverbp.android.ui.achievements.MedalsScreen
import com.silverbp.android.ui.capture.CaptureScreen
import com.silverbp.android.ui.capture.GlucoseCaptureScreen
import com.silverbp.android.ui.chat.ChatScreen
import com.silverbp.android.ui.coach.CoachLogDietScreen
import com.silverbp.android.ui.coach.CoachLogMedicationScreen
import com.silverbp.android.ui.coach.CoachLogSleepScreen
import com.silverbp.android.ui.coach.CoachScreen
import com.silverbp.android.ui.coach.CoachWeeklyPlanScreen
import com.silverbp.android.ui.coach.CoachWeeklyReportScreen
import com.silverbp.android.ui.coach.MedicationEditScreen
import com.silverbp.android.ui.coach.MedicationManageScreen
import com.silverbp.android.ui.confirm.ConfirmGlucoseScreen
import com.silverbp.android.ui.confirm.ConfirmWeightScreen
import com.silverbp.android.ui.confirm.ConfirmReadingScreen
import com.silverbp.android.ui.data.DataHubScreen
import com.silverbp.android.ui.exercise.ExerciseDetailScreen
import com.silverbp.android.ui.exercise.ExerciseHomeScreen
import com.silverbp.android.ui.exercise.ExerciseSessionScreen
import com.silverbp.android.ui.exercise.ExerciseSummaryScreen
import com.silverbp.android.ui.exercise.machine.MachineCaptureScreen
import com.silverbp.android.ui.exercise.machine.MachineConfirmScreen
import com.silverbp.android.ui.member.MemberManagementScreen
import com.silverbp.android.ui.nutrition.BarcodeScanScreen
import com.silverbp.android.ui.nutrition.NutritionConfirmScreen
import com.silverbp.android.ui.nutrition.NutritionScreen
import com.silverbp.android.ui.onboarding.LinkAccountScreen
import com.silverbp.android.ui.onboarding.OnboardingModelScreen
import com.silverbp.android.ui.onboarding.OnboardingNicknameScreen
import com.silverbp.android.ui.report.ReportScreen
import com.silverbp.android.ui.settings.SettingsScreen
import com.silverbp.android.ui.strength.WorkoutSessionScreen
import com.silverbp.android.ui.strength.WorkoutSummaryScreen
import com.silverbp.android.ui.today.TodayScreen

@Composable
fun AppNavHost() {
    val rootNav = rememberNavController()
    val rootSettings by ServiceLocator.userSettings.flow.collectAsStateWithLifecycle(initialValue = null)

    // First-launch gate: when DataStore reports didOnboard == false OR the user
    // hasn't accepted the current privacy policy version, push the onboarding
    // screen on top of HOME and clear HOME so back-press exits the app instead
    // of revealing an un-onboarded HOME. Once both flags are satisfied,
    // nothing happens here on re-launch.
    val needsOnboarding: Boolean? = rootSettings?.let {
        !it.didOnboard || it.acceptedPolicyVersion < CURRENT_PRIVACY_POLICY_VERSION
    }
    LaunchedEffect(needsOnboarding) {
        if (needsOnboarding == true) {
            rootNav.navigate(Routes.ONBOARDING) {
                popUpTo(Routes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Chained second gate: once onboarding is satisfied, let the user pick how AI
    // photo-reading runs (on-device / cloud / AICore), pre-selecting what suits
    // this phone. One-time — the picker's escape hatch also sets pickedAiBackend,
    // so this never re-fires. Chained AFTER needsOnboarding so it never fires
    // while still un-onboarded.
    val needsModelChoice: Boolean? = rootSettings?.let {
        needsOnboarding == false && !it.pickedAiBackend
    }
    LaunchedEffect(needsModelChoice) {
        if (needsModelChoice == true) {
            rootNav.navigate(Routes.ONBOARDING_MODEL) {
                popUpTo(Routes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Chained third gate (Phase 5): once onboarding AND the AI picker are
    // satisfied, prompt for a linked Google account so data is backed up to
    // Drive. Soft gate — a user who can't or won't sign in can tap 「稍後再說」,
    // which sets skippedGoogleLink so this never re-fires. Chained AFTER the
    // model choice so it never fires before the picker; once googleAccountEmail
    // is set it becomes false.
    val needsGoogleSignIn: Boolean? = rootSettings?.let {
        needsOnboarding == false && it.pickedAiBackend &&
            it.googleAccountEmail.isBlank() && !it.skippedGoogleLink
    }
    LaunchedEffect(needsGoogleSignIn) {
        if (needsGoogleSignIn == true) {
            rootNav.navigate(Routes.ONBOARDING_LINK) {
                popUpTo(Routes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Notification tap → navigate root-level sub-routes here; tab routes are
    // handled inside HomeWithTabs (inner nav). DeepLinkBus is a SharedFlow so
    // both layers receive each event.
    LaunchedEffect(Unit) {
        DeepLinkBus.routes.collect { route ->
            when (route) {
                Routes.COACH_WEEKLY_REPORT,
                Routes.COACH_LOG_DIET,
                Routes.COACH_LOG_SLEEP,
                Routes.COACH_LOG_MEDICATION,
                Routes.EXERCISE_SESSION,
                Routes.EXERCISE_SUMMARY -> {
                    // Pop back to home first so the new screen overlays cleanly
                    // even if the user was deep in another modal.
                    rootNav.popBackStack(Routes.HOME, inclusive = false)
                    rootNav.navigate(route)
                }
            }
        }
    }

    NavHost(navController = rootNav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeWithTabs(rootNav) }
        composable(Routes.ONBOARDING) {
            OnboardingNicknameScreen(
                onCompleted = {
                    rootNav.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.ONBOARDING_MODEL) {
            OnboardingModelScreen(
                onCompleted = {
                    rootNav.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING_MODEL) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.ONBOARDING_LINK) {
            LinkAccountScreen(
                onLinked = {
                    rootNav.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING_LINK) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onCaptured = { readingId -> rootNav.navigate(Routes.confirmEdit(readingId)) },
                onManualEntry = { rootNav.navigate(Routes.CONFIRM_NEW) },
                onClose = { rootNav.popBackStack() }
            )
        }
        composable(
            "${Routes.CONFIRM}/{${Routes.ARG_READING_ID}}",
            arguments = listOf(navArgument(Routes.ARG_READING_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_READING_ID)
            ConfirmReadingScreen(
                readingIdArg = id,
                onSaved = {
                    rootNav.popBackStack(Routes.HOME, inclusive = false)
                },
                onCancel = { rootNav.popBackStack() }
            )
        }
        // Blood-glucose capture → confirm (v19). Mirrors the BP CAPTURE/CONFIRM
        // pair: a single confirm pattern handles "new" (manual), "draft" (staged
        // camera read), and an existing-id edit; the screen's initWith routes them.
        composable(Routes.GLUCOSE_CAPTURE) {
            GlucoseCaptureScreen(
                onAnalyzed = {
                    rootNav.navigate(Routes.GLUCOSE_CONFIRM_DRAFT) {
                        popUpTo(Routes.GLUCOSE_CAPTURE) { inclusive = true }
                    }
                },
                onBack = { rootNav.popBackStack() },
            )
        }
        composable(
            "${Routes.GLUCOSE_CONFIRM}/{${Routes.ARG_GLUCOSE_ID}}",
            arguments = listOf(navArgument(Routes.ARG_GLUCOSE_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_GLUCOSE_ID)
            ConfirmGlucoseScreen(
                readingIdArg = id,
                onSaved = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
                onCancel = { rootNav.popBackStack() },
            )
        }
        // Body-weight (體重, v20) confirm. Manual entry only — no capture route. The
        // single pattern handles "new" (manual) and an existing-id edit; the
        // screen's initWith routes them, exactly like the glucose CONFIRM pattern.
        composable(
            "${Routes.WEIGHT_CONFIRM}/{${Routes.ARG_WEIGHT_ID}}",
            arguments = listOf(navArgument(Routes.ARG_WEIGHT_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_WEIGHT_ID)
            ConfirmWeightScreen(
                readingIdArg = id,
                onSaved = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
                onCancel = { rootNav.popBackStack() },
            )
        }
        composable(Routes.EXERCISE_SESSION) {
            ExerciseSessionScreen(
                onFinished = {
                    rootNav.navigate(Routes.EXERCISE_SUMMARY) {
                        popUpTo(Routes.EXERCISE_SESSION) { inclusive = true }
                    }
                },
                onClose = { rootNav.popBackStack() },
            )
        }
        composable(Routes.EXERCISE_SUMMARY) {
            ExerciseSummaryScreen(
                onSaved = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
                onDiscard = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.MACHINE_CAPTURE) {
            MachineCaptureScreen(
                onAnalyzed = {
                    rootNav.navigate(Routes.MACHINE_CONFIRM_NEW) {
                        popUpTo(Routes.MACHINE_CAPTURE) { inclusive = true }
                    }
                },
                onBack = { rootNav.popBackStack() },
            )
        }
        composable(Routes.MACHINE_CONFIRM_NEW) {
            MachineConfirmScreen(
                onSaved = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
                onCancel = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.STRENGTH_SESSION) {
            WorkoutSessionScreen(
                onFinished = {
                    rootNav.navigate(Routes.STRENGTH_SUMMARY) {
                        popUpTo(Routes.STRENGTH_SESSION) { inclusive = true }
                    }
                },
                // Back keeps the live session in the store; the strength
                // library's resume dialog is the re-entry path.
                onClose = { rootNav.popBackStack() },
            )
        }
        composable(Routes.STRENGTH_SUMMARY) {
            WorkoutSummaryScreen(
                onSaved = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
                onDiscard = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(
            Routes.EXERCISE_DETAIL_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_EXERCISE_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_EXERCISE_ID) ?: return@composable
            ExerciseDetailScreen(
                sessionId = id,
                onBack = { rootNav.popBackStack() },
                onDeleted = { rootNav.popBackStack() },
            )
        }
        composable(Routes.MEDALS) {
            MedalsScreen(onBack = { rootNav.popBackStack() })
        }
        // Settings + Report live at the root NavHost so the bottom NavigationBar
        // disappears when they're active — matching iOS sheet/push behavior.
        // popBackStack(HOME, inclusive=false) keeps us safe from accidental app-exit
        // if either route ever gets launched as the start destination.
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onClose = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenSyncPairing = { rootNav.navigate(Routes.SYNC_PAIRING) },
                onOpenManageMedications = { rootNav.navigate(Routes.COACH_MANAGE_MEDICATIONS) },
                onOpenBackup = { rootNav.navigate(Routes.BACKUP) },
                onOpenAdvanced = { rootNav.navigate(Routes.SETTINGS_ADVANCED) },
                onOpenManageMembers = { rootNav.navigate(Routes.MEMBER_MANAGE) },
            )
        }
        composable(Routes.MEMBER_MANAGE) {
            MemberManagementScreen(onClose = { rootNav.popBackStack() })
        }
        composable(Routes.SETTINGS_ADVANCED) {
            com.silverbp.android.ui.settings.AdvancedSettingsScreen(
                onClose = { rootNav.popBackStack() },
            )
        }
        composable(Routes.BACKUP) {
            com.silverbp.android.ui.backup.BackupScreen(
                onBack = { rootNav.popBackStack() },
            )
        }
        composable(Routes.REPORT) {
            ReportScreen(onClose = { rootNav.popBackStack() })
        }
        composable(Routes.CHAT) {
            ChatScreen(onBack = { rootNav.popBackStack() })
        }
        composable(Routes.NUTRITION_CONFIRM_NEW) {
            NutritionConfirmScreen(
                idArg = null,
                onSaved = { rootNav.popBackStack(Routes.HOME, inclusive = false) },
                onCancel = { rootNav.popBackStack() },
            )
        }
        composable(
            Routes.NUTRITION_CONFIRM_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_FOOD_LOG_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_FOOD_LOG_ID)
            NutritionConfirmScreen(
                idArg = id,
                onSaved = { rootNav.popBackStack() },
                onCancel = { rootNav.popBackStack() },
            )
        }
        composable(Routes.NUTRITION_BARCODE) {
            BarcodeScanScreen(
                onResult = {
                    rootNav.navigate(Routes.NUTRITION_CONFIRM_NEW) {
                        popUpTo(Routes.NUTRITION_BARCODE) { inclusive = true }
                    }
                },
                onClose = { rootNav.popBackStack() },
            )
        }
        composable(Routes.SYNC_PAIRING) {
            com.silverbp.android.ui.sync.PairingScreen(
                onBack = { rootNav.popBackStack() },
            )
        }
        // Coach sub-routes — modal-style, hide bottom bar like Settings/Report.
        composable(Routes.COACH_WEEKLY_REPORT) {
            CoachWeeklyReportScreen(onClose = { rootNav.popBackStack() })
        }
        composable(Routes.COACH_WEEKLY_PLAN) {
            CoachWeeklyPlanScreen(onClose = { rootNav.popBackStack() })
        }
        composable(Routes.COACH_LOG_DIET) {
            CoachLogDietScreen(onClose = { rootNav.popBackStack() })
        }
        composable(Routes.COACH_LOG_SLEEP) {
            CoachLogSleepScreen(onClose = { rootNav.popBackStack() })
        }
        composable(Routes.COACH_LOG_MEDICATION) {
            CoachLogMedicationScreen(
                onClose = { rootNav.popBackStack() },
                onManage = { rootNav.navigate(Routes.COACH_MANAGE_MEDICATIONS) },
            )
        }
        composable(Routes.COACH_MANAGE_MEDICATIONS) {
            MedicationManageScreen(
                onClose = { rootNav.popBackStack() },
                onAddNew = { rootNav.navigate(Routes.COACH_EDIT_MEDICATION_NEW) },
                onEdit = { id -> rootNav.navigate(Routes.coachEditMedication(id)) },
            )
        }
        composable(Routes.COACH_EDIT_MEDICATION_NEW) {
            MedicationEditScreen(
                medicationId = null,
                onSaved = { rootNav.popBackStack() },
                onCancel = { rootNav.popBackStack() },
            )
        }
        composable(
            Routes.COACH_EDIT_MEDICATION_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_MEDICATION_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_MEDICATION_ID)
            MedicationEditScreen(
                medicationId = id,
                onSaved = { rootNav.popBackStack() },
                onCancel = { rootNav.popBackStack() },
            )
        }
    }
}

@Composable
private fun HomeWithTabs(rootNav: NavHostController) {
    val tabsNav = rememberNavController()
    val backstack by tabsNav.currentBackStackEntryAsState()
    val currentRoute = backstack?.destination?.route ?: TabDestination.Today.route
    val settings by ServiceLocator.userSettings.flow.collectAsStateWithLifecycle(initialValue = null)
    // Coach tab is gated on the master toggle; History (紀錄) now lives under the
    // always-visible Data tab, so hiding Coach when disabled is safe again.
    // settings == null on cold start: show every tab (matches Coach default-true).
    val visibleTabs = TabDestination.all.filter { tab ->
        tab !is TabDestination.Coach || (settings?.enableCoach ?: true)
    }

    // Notification tap → switch to Coach tab. Sub-routes are handled by the
    // outer NavHost; here we only honour the tab route.
    LaunchedEffect(Unit) {
        DeepLinkBus.routes.collect { route ->
            if (route == TabDestination.Coach.route) {
                tabsNav.navigate(TabDestination.Coach.route) {
                    popUpTo(TabDestination.Today.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    // The AI assistant (聊天) is a floating pill instead of a bottom-nav tab —
    // mirroring Google Health's "詢問教練". It collapses to icon-only while the
    // user scrolls down and expands (icon + label) on scroll-up / at rest.
    var fabExpanded by remember { mutableStateOf(true) }
    val fabScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -1f) fabExpanded = false
                else if (available.y > 1f) fabExpanded = true
                return Offset.Zero
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                visibleTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            tabsNav.navigate(tab.route) {
                                popUpTo(TabDestination.Today.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                        // Only label the active tab so icons keep breathing room.
                        alwaysShowLabel = false,
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { rootNav.navigate(Routes.CHAT) { launchSingleTop = true } },
                expanded = fabExpanded,
                icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                text = { Text(stringResource(R.string.chat_fab_label)) },
            )
        },
    ) { padding ->
        // consumeWindowInsets stops nested Scaffolds (e.g. ChatScreen) from
        // re-applying the system-bar insets the outer Scaffold already padded for.
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .nestedScroll(fabScroll)
        ) {
            NavHost(navController = tabsNav, startDestination = TabDestination.Today.route) {
                tabsGraph(rootNav, tabsNav)
            }
        }
    }
}

private fun NavGraphBuilder.tabsGraph(rootNav: NavHostController, tabsNav: NavHostController) {
    composable(TabDestination.Today.route) {
        // "今天 N 筆" jumps to the Data tab's unified history (紀錄) — same bottom-tab
        // switch the NavigationBar performs, so the day's combined BP+glucose record
        // is right there. Unified history isn't type-filterable (it's one combined
        // list), so both section affordances land on the same place.
        val openHistory: () -> Unit = {
            tabsNav.navigate(TabDestination.Data.route) {
                popUpTo(TabDestination.Today.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        TodayScreen(
            // The "+" is now a chooser (量血壓 / 量血糖). The single onCapture →
            // Routes.CAPTURE was split so the BP option (and the BP section's
            // inline "記一筆") open the BP camera, while the glucose option (and
            // the glucose section's inline "記一筆") open the meter-capture flow,
            // which itself offers manual entry as the always-available path
            // (the emulator can't run LiteRT, so OCR degrades to manual).
            onCaptureBp = { rootNav.navigate(Routes.CAPTURE) },
            onCaptureGlucose = { rootNav.navigate(Routes.GLUCOSE_CAPTURE) },
            // Weight is manual-only (no capture route) → open the confirm form
            // straight away, both from the "+" chooser and the weight section's
            // inline "記一筆".
            onCaptureWeight = { rootNav.navigate(Routes.WEIGHT_CONFIRM_NEW) },
            onAddManual = { rootNav.navigate(Routes.CONFIRM_NEW) },
            onOpenSettings = { rootNav.navigate(Routes.SETTINGS) },
            onManageMembers = { rootNav.navigate(Routes.MEMBER_MANAGE) },
            // Tapping a reading in the unified Today card edits it via the
            // existing Confirm flows (BP ConfirmReading / glucose ConfirmGlucose /
            // weight ConfirmWeight).
            onEditBp = { id -> rootNav.navigate(Routes.confirmEdit(id)) },
            onEditGlucose = { id -> rootNav.navigate(Routes.glucoseConfirmEdit(id)) },
            onEditWeight = { id -> rootNav.navigate(Routes.weightConfirmEdit(id)) },
            // Both "今天 N 筆" affordances open the unified Data-tab history.
            onViewBpHistory = openHistory,
            onViewGlucoseHistory = openHistory,
            onViewWeightHistory = openHistory,
        )
    }
    composable(TabDestination.Coach.route) {
        CoachScreen(
            onOpenWeeklyReport = { rootNav.navigate(Routes.COACH_WEEKLY_REPORT) },
            onOpenWeeklyPlan = { rootNav.navigate(Routes.COACH_WEEKLY_PLAN) },
            onOpenLogDiet = { rootNav.navigate(Routes.COACH_LOG_DIET) },
            onOpenLogSleep = { rootNav.navigate(Routes.COACH_LOG_SLEEP) },
            onOpenLogMedication = { rootNav.navigate(Routes.COACH_LOG_MEDICATION) },
            onStartExercise = { rootNav.navigate(Routes.EXERCISE_SESSION) },
        )
    }
    composable(TabDestination.Exercise.route) {
        ExerciseHomeScreen(
            onStartSession = { rootNav.navigate(Routes.EXERCISE_SESSION) },
            onStartStrengthSession = { rootNav.navigate(Routes.STRENGTH_SESSION) },
            onCaptureMachine = { rootNav.navigate(Routes.MACHINE_CAPTURE) },
            onOpenDetail = { id -> rootNav.navigate(Routes.exerciseDetail(id)) },
            onOpenMedals = { rootNav.navigate(Routes.MEDALS) },
            // Finished 檢查點的還原路徑:跳過運動畫面,直接進摘要頁儲存。
            onOpenSummary = { rootNav.navigate(Routes.EXERCISE_SUMMARY) },
        )
    }
    composable(TabDestination.Data.route) {
        DataHubScreen(
            onEditReading = { id -> rootNav.navigate(Routes.confirmEdit(id)) },
            onOpenReport = { rootNav.navigate(Routes.REPORT) },
            onManageMembers = { rootNav.navigate(Routes.MEMBER_MANAGE) },
            onEditGlucose = { id -> rootNav.navigate(Routes.glucoseConfirmEdit(id)) },
        )
    }
    composable(TabDestination.Nutrition.route) {
        NutritionScreen(
            onOpenConfirmNew = { rootNav.navigate(Routes.NUTRITION_CONFIRM_NEW) },
            onOpenConfirmEdit = { id -> rootNav.navigate(Routes.nutritionConfirmEdit(id)) },
            onOpenBarcode = { rootNav.navigate(Routes.NUTRITION_BARCODE) },
        )
    }
}
