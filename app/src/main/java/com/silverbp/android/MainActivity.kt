package com.silverbp.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.silverbp.android.coach.CoachNotifier
import com.silverbp.android.recognition.ModelBootstrap
import com.silverbp.android.ui.SilverBpApp
import com.silverbp.android.ui.nav.DeepLinkBus
import com.silverbp.android.ui.theme.SilverBpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        forwardDeepLink(intent)
        setContent {
            SilverBpTheme {
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
        forwardDeepLink(intent)
    }

    private fun forwardDeepLink(intent: Intent?) {
        val route = intent?.getStringExtra(CoachNotifier.EXTRA_COACH_ROUTE) ?: return
        if (route.isNotBlank()) DeepLinkBus.emit(route)
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
