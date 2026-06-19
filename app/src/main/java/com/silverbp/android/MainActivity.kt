package com.silverbp.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.silverbp.android.coach.CoachNotifier
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

    private fun forwardDeepLink(intent: Intent?) {
        val route = intent?.getStringExtra(CoachNotifier.EXTRA_COACH_ROUTE) ?: return
        if (route.isNotBlank()) emitDeepLink(route)
    }

    /**
     * Defer emission to the lifecycle scope so the AppNavHost LaunchedEffect
     * collector has subscribed to [DeepLinkBus] by the time we emit —
     * otherwise on cold start the SharedFlow(replay=0) drops the event.
     */
    private fun emitDeepLink(route: String) {
        lifecycleScope.launch { DeepLinkBus.emit(route) }
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
