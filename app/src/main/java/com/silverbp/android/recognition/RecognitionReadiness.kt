package com.silverbp.android.recognition

import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Whether camera recognition can run right now. The Local backend needs the
 * on-device model fully loaded ([ModelLoadPhase.Ready]); Cloud/AICore don't
 * touch the local file so they're always ready. Capture screens gate their
 * shutter / photo-pick on [ready] and surface [showModelBanner] otherwise.
 */
data class RecognitionReadiness(
    val backendIsLocal: Boolean,
    val phase: ModelLoadPhase,
) {
    val ready: Boolean get() = !backendIsLocal || phase is ModelLoadPhase.Ready

    /** Show the download/loading banner only for a not-yet-ready local model. */
    val showModelBanner: Boolean get() = backendIsLocal && phase !is ModelLoadPhase.Ready
}

/** Shared readiness [StateFlow] for any capture screen's ViewModel. */
fun recognitionReadinessFlow(scope: CoroutineScope): StateFlow<RecognitionReadiness> =
    combine(
        ServiceLocator.userSettings.flow,
        ServiceLocator.modelLoadStatus.phase,
    ) { user, phase ->
        RecognitionReadiness(user.recognitionBackend == RecognitionBackend.Local, phase)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        RecognitionReadiness(backendIsLocal = false, phase = ModelLoadPhase.Idle),
    )
