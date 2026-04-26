package com.silverbp.android.recognition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mirrors iOS ModelLoadStatus.Phase 1:1 */
sealed class ModelLoadPhase {
    data object Idle : ModelLoadPhase()
    data class Downloading(val fraction: Float, val variantId: String? = null) : ModelLoadPhase()
    data object Loading : ModelLoadPhase()
    data object Ready : ModelLoadPhase()
    data class Failed(val message: String) : ModelLoadPhase()
}

/** Application-scoped singleton (held by ServiceLocator). */
class ModelLoadStatus {
    private val _phase = MutableStateFlow<ModelLoadPhase>(ModelLoadPhase.Idle)
    val phase: StateFlow<ModelLoadPhase> = _phase.asStateFlow()

    fun set(phase: ModelLoadPhase) {
        _phase.value = phase
    }

    val isBusy: Boolean
        get() = when (_phase.value) {
            is ModelLoadPhase.Downloading, is ModelLoadPhase.Loading -> true
            else -> false
        }
}
