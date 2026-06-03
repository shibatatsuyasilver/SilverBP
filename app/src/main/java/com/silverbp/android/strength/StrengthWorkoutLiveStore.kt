package com.silverbp.android.strength

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Per-exercise block inside a live workout: the chosen [exercise] and its set
 * logs being edited. [skipped] marks an exercise the user passed on entirely.
 */
data class LiveExercise(
    val exercise: ExerciseCatalogItem,
    val sets: List<SetLog>,
    val skipped: Boolean = false,
)

/**
 * Live snapshot of an in-progress strength workout. Held by the singleton
 * [StrengthWorkoutLiveStore]; consumed by ui ViewModels via
 * [StrengthWorkoutLiveStore.flow]. Never persisted directly — see
 * [StrengthWorkoutLiveStore.snapshotAndFinish].
 */
data class StrengthWorkoutLive(
    val id: String,
    val startedAtMillis: Long,
    val runState: StrengthRunState,
    val exercises: List<LiveExercise>,
    val currentIndex: Int,
    val note: String,
    val difficulty: DifficultyFeedback?,
) {
    val currentExercise: LiveExercise? get() = exercises.getOrNull(currentIndex)

    val totalSets: Int get() = exercises.sumOf { it.sets.size }

    val completedSets: Int
        get() = exercises.sumOf { ex -> ex.sets.count { it.isCompleted } }
}

/** Lifecycle of a live strength workout. Idle → Running → Finished → Idle. */
enum class StrengthRunState { Idle, Running, Finished }

/**
 * Process-wide singleton holding the live state of the active strength workout.
 * In-memory only, modeled on [com.silverbp.android.exercise.ExerciseSessionLiveStore]:
 *
 * - Started via [start] with the chosen exercises.
 * - Mutated via [logSet] / [markSetComplete] / [skipExercise] / [setCurrentIndex] / [addNote].
 * - Finalised via [snapshotAndFinish] which returns a draft [StrengthWorkoutSession]
 *   for the caller to persist; in-memory state is preserved (Finished) so a
 *   summary screen can still read it. Call [clear] once persistence is done.
 */
class StrengthWorkoutLiveStore {

    private val _flow = MutableStateFlow<StrengthWorkoutLive?>(null)
    val flow: StateFlow<StrengthWorkoutLive?> = _flow.asStateFlow()

    /** Begin a workout from the chosen exercises (no sets logged yet). */
    fun start(exercises: List<ExerciseCatalogItem>, startedAtMillis: Long = System.currentTimeMillis()) {
        _flow.value = StrengthWorkoutLive(
            id = UUID.randomUUID().toString(),
            startedAtMillis = startedAtMillis,
            runState = StrengthRunState.Running,
            exercises = exercises.map { LiveExercise(exercise = it, sets = emptyList()) },
            currentIndex = 0,
            note = "",
            difficulty = null,
        )
    }

    /** Move focus to another exercise in the workout. */
    fun setCurrentIndex(index: Int) = mutate { cur ->
        if (index !in cur.exercises.indices) cur else cur.copy(currentIndex = index)
    }

    /**
     * Append or replace a set for [exerciseId]. If a set with [SetLog.setNumber]
     * already exists it is replaced; otherwise the set is appended.
     */
    fun logSet(exerciseId: String, set: SetLog) = mutate { cur ->
        cur.updateExercise(exerciseId) { ex ->
            val existing = ex.sets.indexOfFirst { it.setNumber == set.setNumber }
            val nextSets = if (existing >= 0) {
                ex.sets.toMutableList().also { it[existing] = set }
            } else {
                ex.sets + set
            }
            ex.copy(sets = nextSets)
        }
    }

    /** Toggle/force the completed flag of a logged set. */
    fun markSetComplete(exerciseId: String, setNumber: Int, completed: Boolean = true) = mutate { cur ->
        cur.updateExercise(exerciseId) { ex ->
            ex.copy(
                sets = ex.sets.map {
                    if (it.setNumber == setNumber) it.copy(isCompleted = completed) else it
                },
            )
        }
    }

    /** Mark an entire exercise as skipped (its sets stay for the record). */
    fun skipExercise(exerciseId: String) = mutate { cur ->
        cur.updateExercise(exerciseId) { it.copy(skipped = true) }
    }

    /** Set the free-text session note. */
    fun addNote(note: String) = mutate { it.copy(note = note) }

    /** Set the post-workout difficulty self-report. */
    fun setDifficulty(difficulty: DifficultyFeedback?) = mutate { it.copy(difficulty = difficulty) }

    /**
     * Build the persisted [StrengthWorkoutSession] draft and mark the live
     * state Finished. Skipped exercises contribute their logged sets (each
     * carrying [SetLog.skipped]); exercises with no sets are dropped. Caller
     * persists; in-memory state is preserved for a summary screen. Call [clear]
     * afterwards.
     */
    fun snapshotAndFinish(endedAtMillis: Long = System.currentTimeMillis()): StrengthWorkoutSession? {
        val cur = _flow.value ?: return null
        val items = cur.exercises
            .filter { it.sets.isNotEmpty() }
            .map { ex ->
                val sets = if (ex.skipped) ex.sets.map { it.copy(skipped = true) } else ex.sets
                ex.exercise to sets
            }
        val session = StrengthWorkoutSession(
            id = cur.id,
            startedAt = cur.startedAtMillis,
            endedAt = endedAtMillis,
            note = cur.note,
            difficulty = cur.difficulty,
            items = items,
        )
        _flow.value = cur.copy(runState = StrengthRunState.Finished)
        return session
    }

    fun clear() {
        _flow.value = null
    }

    private inline fun mutate(transform: (StrengthWorkoutLive) -> StrengthWorkoutLive) {
        val cur = _flow.value ?: return
        _flow.value = transform(cur)
    }

    private inline fun StrengthWorkoutLive.updateExercise(
        exerciseId: String,
        transform: (LiveExercise) -> LiveExercise,
    ): StrengthWorkoutLive = copy(
        exercises = exercises.map { if (it.exercise.id == exerciseId) transform(it) else it },
    )
}
