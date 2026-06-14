package com.silverbp.android.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.ui.graphics.vector.ImageVector
import com.silverbp.android.R

sealed class TabDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Today    : TabDestination("today",    R.string.tab_today,    Icons.Filled.Home)
    // Data hub hosts the BP history list (紀錄) + analytics (分析), mirroring iOS DataHubView.
    data object Data     : TabDestination("data",     R.string.tab_data,     Icons.Filled.Assessment)
    data object Exercise : TabDestination("exercise", R.string.tab_exercise, Icons.AutoMirrored.Filled.DirectionsRun)
    data object Nutrition: TabDestination("nutrition", R.string.tab_nutrition, Icons.Filled.Restaurant)
    data object Coach    : TabDestination("coach",    R.string.tab_coach,    Icons.Filled.Favorite)

    companion object {
        // Lazy because Kotlin initialises the companion object before the
        // sealed class's data-object children, so eager listOf(...) here
        // would capture nulls and crash NavigationBar at first render.
        val all: List<TabDestination> by lazy {
            listOf(Today, Data, Exercise, Nutrition, Coach)
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

    // Blood-glucose (血糖, v19) capture → confirm flow. Root-level so the bottom
    // bar hides while active, mirroring the BP CAPTURE/CONFIRM routes. CONFIRM
    // reads the staged OCR/camera draft from GlucoseCaptureSessionHolder when the
    // arg is "draft"; "new" is manual entry; the {glucoseId} pattern edits an
    // existing row.
    const val GLUCOSE_CAPTURE = "glucose/capture"
    const val GLUCOSE_CONFIRM = "glucose/confirm"           // glucose/confirm/{glucoseId}
    const val GLUCOSE_CONFIRM_NEW = "glucose/confirm/new"   // manual entry
    const val GLUCOSE_CONFIRM_DRAFT = "glucose/confirm/draft" // consume staged camera draft
    fun glucoseConfirmEdit(id: String) = "glucose/confirm/$id"
    const val ARG_GLUCOSE_ID = "glucoseId"

    // Body-weight (體重, v20) confirm flow. Manual entry only this round (no scale
    // OCR), so there is NO capture route — the "+" chooser and the weight section's
    // inline "記一筆" both open WEIGHT_CONFIRM_NEW directly. The single confirm
    // pattern handles "new" (manual) and an existing-id edit; the screen's initWith
    // routes them. Root-level so the bottom bar hides while active, like the BP /
    // glucose CONFIRM routes.
    const val WEIGHT_CONFIRM = "weight/confirm"            // weight/confirm/{weightId}
    const val WEIGHT_CONFIRM_NEW = "weight/confirm/new"    // manual entry
    fun weightConfirmEdit(id: String) = "weight/confirm/$id"
    const val ARG_WEIGHT_ID = "weightId"

    const val EXERCISE_SESSION = "exercise/session"
    const val EXERCISE_SUMMARY = "exercise/summary"
    fun exerciseDetail(id: String) = "exercise/detail/$id"

    // Gym-machine console OCR: capture → confirm. Root-level so the bottom bar
    // hides while active, like EXERCISE_SESSION. CONFIRM reads the staged
    // readout from MachineWorkoutDraftHolder.
    const val MACHINE_CAPTURE = "exercise/machine/capture"
    const val MACHINE_CONFIRM_NEW = "exercise/machine/confirm/new"

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

    // AI assistant chat — opened from the floating pill on HomeWithTabs (not a tab).
    const val CHAT = "chat"

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

    // Family-member management (v18) — opened from a Settings card. Screen comes
    // in a later iteration; this is the route constant the editor/list will use.
    const val MEMBER_MANAGE = "member/manage"

    // Owner profile (個人資料) — a FREE single-user shortcut from the Settings
    // 個人資料 card that opens the owner member editor directly (no member list,
    // no AddMember paywall), distinct from MEMBER_MANAGE.
    const val PROFILE = "member/profile"

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

    // First-launch AI backend picker. Routed from AppNavHost after onboarding is
    // complete but before the Google sign-in gate, while UserSettings.pickedAiBackend
    // is false. Clears itself from the back stack on completion.
    const val ONBOARDING_MODEL = "onboarding/model"
}
