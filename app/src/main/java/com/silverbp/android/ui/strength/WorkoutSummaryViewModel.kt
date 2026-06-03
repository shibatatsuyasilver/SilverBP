package com.silverbp.android.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.coach.BpWorkoutAssociationRepository
import com.silverbp.android.core.BpRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.strength.DifficultyFeedback
import com.silverbp.android.strength.StrengthWorkoutLive
import com.silverbp.android.strength.StrengthWorkoutLiveStore
import com.silverbp.android.strength.StrengthWorkoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reads the finished snapshot left in [StrengthWorkoutLiveStore] by
 * [WorkoutSessionViewModel.finish], asks the user how the workout felt, then
 * persists the session (with the chosen difficulty) or discards it.
 */
class WorkoutSummaryViewModel(
    private val repo: StrengthWorkoutRepository = ServiceLocator.strengthWorkoutRepository,
    private val liveStore: StrengthWorkoutLiveStore = ServiceLocator.strengthWorkoutLiveStore,
    private val bpRepo: BpRepository = ServiceLocator.bpRepository,
    private val assocRepo: BpWorkoutAssociationRepository = ServiceLocator.bpWorkoutAssociationRepository,
) : ViewModel() {

    /** Finished live workout snapshot, or null if nothing to summarise. */
    val live: StrengthWorkoutLive? = liveStore.flow.value

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    // Whether a BP reading exists within the post-window — drives the summary's
    // "已連結 / 量運動後血壓" affordance. Re-checked when the screen resumes.
    private val _hasRecentPostBp = MutableStateFlow(false)
    val hasRecentPostBp: StateFlow<Boolean> = _hasRecentPostBp.asStateFlow()

    fun refreshHasRecentPostBp() {
        viewModelScope.launch { _hasRecentPostBp.value = findRecentPostBpId() != null }
    }

    fun save(difficulty: DifficultyFeedback, onDone: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        liveStore.setDifficulty(difficulty)
        val draft = liveStore.snapshotAndFinish()
        if (draft == null) {
            _saving.value = false
            return onDone()
        }
        viewModelScope.launch {
            repo.upsert(draft)
            linkRecentPostBp(draft.id)
            liveStore.clear()
            _saving.value = false
            onDone()
        }
    }

    fun discard(onDone: () -> Unit) {
        liveStore.clear()
        onDone()
    }

    private suspend fun findRecentPostBpId(): String? {
        val now = java.time.Instant.now()
        val from = now.minus(POST_BP_WINDOW_MIN, java.time.temporal.ChronoUnit.MINUTES)
        return bpRepo.observeRange(from, now).first()
            .maxByOrNull { it.timestamp }?.id?.toString()
    }

    /**
     * Best-effort: if the user measured BP within [POST_BP_WINDOW_MIN] minutes,
     * link it to this session as the "post" reading. Silent no-op if none.
     */
    private suspend fun linkRecentPostBp(sessionId: String) {
        val bpId = findRecentPostBpId() ?: return
        assocRepo.addAssociation(
            bpReadingId = bpId,
            sessionId = sessionId,
            sessionType = "strength",
            contextType = "post",
        )
    }

    companion object {
        // Window in which a BP reading counts as this session's "post" BP.
        private const val POST_BP_WINDOW_MIN = 30L
    }
}
