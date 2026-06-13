package com.silverbp.android.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.coach.BpWorkoutAssociationRepository
import com.silverbp.android.core.BpRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.exercise.ExerciseSessionLiveStore
import com.silverbp.android.exercise.RoutePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reads the finalized snapshot left in [ExerciseSessionLiveStore] by
 * [ExerciseController.stop], lets the user edit a note and Save (write to
 * Room + Health Connect best-effort) or Discard.
 */
class ExerciseSummaryViewModel(
    private val repo: ExerciseRepository = ServiceLocator.exerciseRepository,
    private val liveStore: ExerciseSessionLiveStore = ServiceLocator.exerciseLiveStore,
    private val bpRepo: BpRepository = ServiceLocator.bpRepository,
    private val assocRepo: BpWorkoutAssociationRepository = ServiceLocator.bpWorkoutAssociationRepository,
) : ViewModel() {

    val session: ExerciseSession?
    val points: List<RoutePoint>

    init {
        val live = liveStore.flow.value
        if (live != null) {
            session = ExerciseSession(
                id = live.id,
                kind = live.kind,
                startedAt = live.startedAt,
                endedAt = java.time.Instant.now(),
                activeDurationMillis = live.activeDurationMillis,
                distanceMeters = live.accumulatedDistanceMeters,
                stepCount = live.stepCount,
                averagePaceSecPerKm = live.paceSecPerKm,
                source = com.silverbp.android.exercise.ExerciseSource.Gps,
                note = "",
                hcRecordId = null,
            )
            points = live.routePoints
        } else {
            session = null
            points = emptyList()
        }
    }

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    // Whether a BP reading exists within the post-window — drives the summary's
    // "已連結 / 量運動後血壓" affordance. Re-checked when the screen resumes.
    private val _hasRecentPostBp = MutableStateFlow(false)
    val hasRecentPostBp: StateFlow<Boolean> = _hasRecentPostBp.asStateFlow()

    fun refreshHasRecentPostBp() {
        viewModelScope.launch { _hasRecentPostBp.value = findRecentPostBpId() != null }
    }

    private suspend fun findRecentPostBpId(): String? {
        val now = java.time.Instant.now()
        // Clamp the window's lower bound to the session's start: a true "post"
        // reading happens after the workout, so the pre-workout gate reading
        // must never qualify. Without this, sessions shorter than
        // POST_BP_WINDOW_MIN would link the pre-workout BP as "post".
        val windowStart = now.minus(POST_BP_WINDOW_MIN, java.time.temporal.ChronoUnit.MINUTES)
        val sessionStart = session?.startedAt
        val from = if (sessionStart != null && sessionStart.isAfter(windowStart)) sessionStart else windowStart
        // Exercise / post-workout BP is owner-only in Phase 1 (sessions aren't
        // member-scoped), so we read the owner's recent BP.
        val ownerId = ServiceLocator.memberRepository.ownerId()
        return bpRepo.observeRange(ownerId, from, now).first()
            .maxByOrNull { it.timestamp }?.id?.toString()
    }

    fun save(note: String, onDone: () -> Unit) {
        val s = session ?: return onDone()
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            repo.upsert(s.copy(note = note), points)
            linkRecentPostBp(s.id.toString())
            liveStore.clear()
            _saving.value = false
            onDone()
        }
    }

    /**
     * Best-effort: if the user measured BP within [POST_BP_WINDOW_MIN] minutes,
     * link it to this session as the "post" reading. Silent no-op if none —
     * the screen offers a "量運動後血壓" affordance instead.
     */
    private suspend fun linkRecentPostBp(sessionId: String) {
        val bpId = findRecentPostBpId() ?: return
        assocRepo.addAssociation(
            bpReadingId = bpId,
            sessionId = sessionId,
            sessionType = "cardio",
            contextType = "post",
        )
    }

    fun discard(onDone: () -> Unit) {
        liveStore.clear()
        onDone()
    }

    companion object {
        // Window in which a BP reading counts as this session's "post" BP.
        private const val POST_BP_WINDOW_MIN = 30L
    }
}
