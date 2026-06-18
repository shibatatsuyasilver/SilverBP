package com.silverbp.android.recognition

import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Whether camera recognition can run right now. Local and AICore both depend on
 * a warmed on-device model ([ModelLoadPhase.Ready]); Cloud only needs settings
 * and is not represented by this model-load phase. Capture screens gate their
 * shutter / photo-pick on [ready] and surface [showModelBanner] otherwise.
 */
data class RecognitionReadiness(
    val backend: RecognitionBackend,
    val phase: ModelLoadPhase,
) {
    val ready: Boolean get() = when (backend) {
        RecognitionBackend.Cloud -> true
        RecognitionBackend.Local,
        RecognitionBackend.AICore -> phase is ModelLoadPhase.Ready
    }

    /** Show the loading banner only for on-device backends that are not ready yet. */
    val showModelBanner: Boolean get() =
        backend != RecognitionBackend.Cloud && phase !is ModelLoadPhase.Ready
}

/** Shared readiness [StateFlow] for any capture screen's ViewModel. */
fun recognitionReadinessFlow(scope: CoroutineScope): StateFlow<RecognitionReadiness> =
    combine(
        ServiceLocator.userSettings.flow,
        ServiceLocator.modelLoadStatus.phase,
    ) { user, phase ->
        RecognitionReadiness(user.recognitionBackend, phase)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        RecognitionReadiness(backend = RecognitionBackend.Cloud, phase = ModelLoadPhase.Idle),
    )
