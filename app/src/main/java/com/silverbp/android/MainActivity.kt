package com.silverbp.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.coach.CoachNotifier
import com.silverbp.android.coach.MedicationActionReceiver
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseNotification
import com.silverbp.android.recognition.ModelBootstrap
import com.silverbp.android.settings.AppThemeMode
import com.silverbp.android.ui.SilverBpApp
import com.silverbp.android.ui.nav.DeepLinkBus
import com.silverbp.android.ui.nav.Routes
import com.silverbp.android.ui.theme.SilverBpTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// FragmentActivity (a ComponentActivity subclass) is required by
// androidx.biometric.BiometricPrompt for the app-lock gate.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleStopAndReview(intent)
        // savedInstanceState == null gates the dose write to the first launch:
        // recreation after process death re-delivers the ORIGINAL intent with
        // extras intact (removeExtra only mutates the app-side copy), and
        // FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY is not set on that path.
        if (savedInstanceState == null) handleMedicationBodyTap(intent)
        forwardDeepLink(intent)
        setContent {
            // Observe only the theme mode here (a second cheap subscription to the
            // same DataStore-backed flow that SilverBpApp collects). Initial value
            // == the persisted default so dark users never see a light flash on
            // cold start.
            val themeModeFlow = remember {
                ServiceLocator.userSettings.flow.map { it.appThemeMode }
            }
            val themeMode by themeModeFlow.collectAsStateWithLifecycle(
                initialValue = AppThemeMode.Dark,
            )
            SilverBpTheme(themeMode = themeMode) {
                SilverBpApp()
            }
        }
    }

    /**
     * Fired when an existing instance is brought to the front (FLAG_ACTIVITY_SINGLE_TOP)
     * — that's how all Coach notifications resume the app without a fresh activity.
     * Without this override the launching intent's [CoachNotifier.EXTRA_COACH_ROUTE]
     * would be ignored on warm start.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStopAndReview(intent)
        handleMedicationBodyTap(intent)
        forwardDeepLink(intent)
    }

    /**
     * The exercise-notification Stop action launches this Activity with
     * [ExerciseNotification.EXTRA_STOP_AND_REVIEW] = true. We finish the
     * session via the controller (snapshots LiveStore + stops the service)
     * and route the user to the Summary screen so they save / discard the
     * just-finished workout. Extra is cleared after handling so a config-
     * change recreation doesn't double-stop.
     */
    private fun handleStopAndReview(intent: Intent?) {
        if (intent?.getBooleanExtra(ExerciseNotification.EXTRA_STOP_AND_REVIEW, false) != true) return
        intent.removeExtra(ExerciseNotification.EXTRA_STOP_AND_REVIEW)
        ServiceLocator.exerciseController.stop()
        emitDeepLink(Routes.EXERCISE_SUMMARY)
    }

    /**
     * The medication reminder's body tap carries the same extras as its
     * "mark taken" action, so tapping the body also records the dose before
     * navigating (navigation itself still flows through [forwardDeepLink]
     * via [CoachNotifier.EXTRA_COACH_ROUTE]). Notifications posted before
     * these extras existed yield null from `markTakenDose` and fall through
     * to navigation-only.
     *
     * Fire-once guards, each covering a distinct re-delivery path:
     *  - `savedInstanceState == null` at the onCreate call site — recreation
     *    after process death re-delivers the original intent with extras
     *    intact (system-side ActivityRecord is untouched by removeExtra).
     *  - LAUNCHED_FROM_HISTORY — a trimmed task's base intent relaunched
     *    from Recents arrives with null saved state, so the flag is the
     *    only signal there.
     *  - removeExtra — same-process re-runs on the retained Intent object.
     * Worst case on a miss, the deterministic doseId makes the upsert
     * idempotent, but it could still overwrite a dose the user un-toggled.
     */
    private fun handleMedicationBodyTap(intent: Intent?) {
        intent ?: return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val dose = MedicationActionReceiver.markTakenDose(
            medicationId = intent.getStringExtra(MedicationActionReceiver.EXTRA_MEDICATION_ID),
            scheduleId = intent.getStringExtra(MedicationActionReceiver.EXTRA_SCHEDULE_ID),
            dayStart = intent.getLongExtra(MedicationActionReceiver.EXTRA_DAY_START, -1L),
            scheduledHour = intent.getIntExtra(MedicationActionReceiver.EXTRA_SCHEDULED_HOUR, -1),
            scheduledMinute = intent.getIntExtra(MedicationActionReceiver.EXTRA_SCHEDULED_MINUTE, 0),
            nowMs = System.currentTimeMillis(),
        ) ?: return
        intent.removeExtra(MedicationActionReceiver.EXTRA_MEDICATION_ID)
        intent.removeExtra(MedicationActionReceiver.EXTRA_SCHEDULE_ID)
        intent.removeExtra(MedicationActionReceiver.EXTRA_DAY_START)
        intent.removeExtra(MedicationActionReceiver.EXTRA_SCHEDULED_HOUR)
        intent.removeExtra(MedicationActionReceiver.EXTRA_SCHEDULED_MINUTE)
        // The receiver's process-scoped IO scope, not lifecycleScope: the
        // write must survive a quick activity teardown (e.g. app-lock gate
        // recreating the activity right after launch).
        MedicationActionReceiver.ioScope.launch {
            try {
                ServiceLocator.coachRepository.upsertDose(dose)
            } catch (t: Throwable) {
                Log.w("MainActivity", "[MedBodyTap] mark-taken failed", t)
            }
        }
    }

    private fun forwardDeepLink(intent: Intent?) {
        val route = intent?.getStringExtra(CoachNotifier.EXTRA_COACH_ROUTE) ?: return
        if (route.isNotBlank()) emitDeepLink(route)
    }

    /**
     * Forward the route to [DeepLinkBus]. The bus now retains the last route
     * (replay = 1, issue #26), so emitting here from `onCreate` — before the
     * NavHost collector has subscribed on cold start — is safe: the late
     * subscriber still replays it. No lifecycle-scope deferral needed.
     */
    private fun emitDeepLink(route: String) {
        DeepLinkBus.emit(route)
    }

    override fun onDestroy() {
        // Release the native LiteRT engine + OpenCL GPU context when the user
        // truly exits (back-out / swipe-from-recents). A leaked OpenCL context
        // can wedge the GPU driver until the phone is rebooted.
        if (isFinishing) {
            ModelBootstrap.shutdown()
        }
        super.onDestroy()
    }
}
