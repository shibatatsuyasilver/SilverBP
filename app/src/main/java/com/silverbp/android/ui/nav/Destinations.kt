package com.silverbp.android.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.ui.graphics.vector.ImageVector
import com.silverbp.android.R

sealed class TabDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Today    : TabDestination("today",    R.string.tab_today,    Icons.Filled.Home)
    // Coach also hosts the BP history list as a sub-tab (see CoachHubScreen).
    data object Coach    : TabDestination("coach",    R.string.tab_coach,    Icons.Filled.Favorite)
    data object Exercise : TabDestination("exercise", R.string.tab_exercise, Icons.AutoMirrored.Filled.DirectionsRun)
    data object Insights : TabDestination("insights", R.string.tab_insights, Icons.Filled.Assessment)
    data object Nutrition: TabDestination("nutrition", R.string.tab_nutrition, Icons.Filled.Restaurant)
    data object Chat     : TabDestination("chat",     R.string.tab_chat,     Icons.AutoMirrored.Filled.Chat)

    companion object {
        // Lazy because Kotlin initialises the companion object before the
        // sealed class's data-object children, so eager listOf(...) here
        // would capture nulls and crash NavigationBar at first render.
        val all: List<TabDestination> by lazy {
            listOf(Today, Coach, Exercise, Insights, Nutrition, Chat)
        }
    }
}

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val CONFIRM = "confirm"           // confirm/{readingId?}
    const val CONFIRM_NEW = "confirm/new"   // brand-new manual entry
    fun confirmEdit(id: String) = "confirm/$id"
    const val ARG_READING_ID = "readingId"

    const val EXERCISE_SESSION = "exercise/session"
    const val EXERCISE_SUMMARY = "exercise/summary"
    fun exerciseDetail(id: String) = "exercise/detail/$id"

    // Strength workout flow — root-level so the bottom bar hides while active,
    // mirroring EXERCISE_SESSION/EXERCISE_SUMMARY. The library + exercise detail
    // live inside the Exercise hub tab, so they need no root routes.
    const val STRENGTH_SESSION = "strength/session"
    const val STRENGTH_SUMMARY = "strength/summary"
    const val EXERCISE_DETAIL_PATTERN = "exercise/detail/{exerciseId}"
    const val ARG_EXERCISE_ID = "exerciseId"

    const val MEDALS = "achievements/medals"

    // Non-tab modal routes hosted by the root NavHost. Settings is opened
    // from TodayScreen's gear icon; Report is opened from InsightsScreen's
    // bottom button. Both hide the bottom NavigationBar while active.
    const val SETTINGS = "settings"
    const val SETTINGS_ADVANCED = "settings/advanced"
    const val REPORT = "report"

    // Coach sub-routes hosted by the root NavHost (hide bottom bar while active).
    // Entry points are inside the Coach tab; later iterations may also link from
    // notifications via deep links.
    const val COACH_WEEKLY_REPORT = "coach/weekly-report"
    const val COACH_WEEKLY_PLAN = "coach/weekly-plan"
    const val COACH_LOG_DIET = "coach/log-diet"
    const val COACH_LOG_SLEEP = "coach/log-sleep"
    const val COACH_LOG_MEDICATION = "coach/log-medication"

    // Medication management — opened from CoachLogMedicationScreen.
    const val COACH_MANAGE_MEDICATIONS = "coach/manage-medications"
    const val COACH_EDIT_MEDICATION_NEW = "coach/edit-medication/new"
    const val COACH_EDIT_MEDICATION_PATTERN = "coach/edit-medication/{medicationId}"
    const val ARG_MEDICATION_ID = "medicationId"
    fun coachEditMedication(id: String) = "coach/edit-medication/$id"

    // Nutrition (飲食) capture → confirm flow. Confirm is root-level so the
    // bottom bar hides while active, like the BP CONFIRM routes. NEW reads the
    // staged draft from NutritionDraftHolder; the {foodLogId} pattern edits an
    // existing row.
    const val NUTRITION_CONFIRM_NEW = "nutrition/confirm/new"
    const val NUTRITION_CONFIRM_PATTERN = "nutrition/confirm/{foodLogId}"
    const val ARG_FOOD_LOG_ID = "foodLogId"
    fun nutritionConfirmEdit(id: String) = "nutrition/confirm/$id"
    const val NUTRITION_BARCODE = "nutrition/barcode"

    // Cross-device sync pairing — opened from SettingsScreen's "跨裝置同步" row.
    const val SYNC_PAIRING = "sync/pairing"

    // Backup / restore — encrypted .sbpbk snapshot export/import flow.
    // Opened from SettingsScreen's "資料備份" row.
    const val BACKUP = "backup"

    // First-launch nickname capture. Routed from AppNavHost when
    // UserSettings.didOnboard is false; clears itself from the back stack on
    // completion so users cannot navigate back into it.
    const val ONBOARDING = "onboarding"

    // First-launch Google sign-in gate (Phase 5). Routed from AppNavHost after
    // onboarding is complete but while UserSettings.googleAccountEmail is blank.
    // Hard gate — clears itself from the back stack once the account is linked.
    const val ONBOARDING_LINK = "onboarding/link"
}
