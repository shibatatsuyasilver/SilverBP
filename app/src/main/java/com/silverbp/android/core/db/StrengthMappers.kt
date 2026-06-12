package com.silverbp.android.core.db

import com.silverbp.android.strength.BodyPart
import com.silverbp.android.strength.DifficultyFeedback
import com.silverbp.android.strength.ExerciseCatalogItem
import com.silverbp.android.strength.SetLog
import com.silverbp.android.strength.StrengthWorkoutSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Strength domain ↔ persistence mappers. Muscle groups are serialised as a
 * JSON String[] inside [ExerciseCatalogItemEntity.muscleGroupsJson] — same
 * tolerant-codec approach as [CoachMappers].
 */

private val strengthJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val stringListSerializer = ListSerializer(String.serializer())

fun ExerciseCatalogItem.toEntity(createdAt: Long, updatedAt: Long) = ExerciseCatalogItemEntity(
    id = id,
    name = name,
    bodyPart = bodyPart.raw,
    muscleGroupsJson = strengthJson.encodeToString(stringListSerializer, muscleGroups),
    description = description,
    isFavorite = isFavorite,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// Pure structural mapping: returns the stored (canonical) bytes verbatim so the
// entity↔domain round-trip is identity and stays unit-testable without a Context.
// Display localization is applied at the repository/UI edge via
// [com.silverbp.android.strength.localized], not here.
fun ExerciseCatalogItemEntity.toDomain() = ExerciseCatalogItem(
    id = id,
    name = name,
    bodyPart = BodyPart.fromRaw(bodyPart),
    muscleGroups = runCatching {
        strengthJson.decodeFromString(stringListSerializer, muscleGroupsJson)
    }.getOrDefault(emptyList()),
    description = description,
    isFavorite = isFavorite,
)

fun SetLog.toEntity(workoutSessionId: String, createdAt: Long) = SetLogEntity(
    id = id,
    workoutSessionId = workoutSessionId,
    exerciseId = exerciseId,
    setNumber = setNumber,
    reps = reps,
    weightKg = weightKg,
    isCompleted = isCompleted,
    skipped = skipped,
    notes = notes,
    createdAt = createdAt,
)

fun SetLogEntity.toDomain() = SetLog(
    id = id,
    exerciseId = exerciseId,
    setNumber = setNumber,
    reps = reps,
    weightKg = weightKg,
    isCompleted = isCompleted,
    skipped = skipped,
    notes = notes,
)

fun StrengthWorkoutSession.toEntity(createdAt: Long, updatedAt: Long) = StrengthWorkoutSessionEntity(
    id = id,
    startedAt = startedAt,
    endedAt = endedAt,
    note = note,
    difficultyRaw = difficulty?.raw,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * Rebuild the full session graph from its row + child rows. [setsByExercise]
 * is the session's [SetLogEntity] list grouped by `exerciseId`; [catalog] is
 * the lookup used to attach the [ExerciseCatalogItem] for each exercise that
 * has sets, preserving first-appearance order.
 */
fun StrengthWorkoutSessionEntity.toDomain(
    sets: List<SetLogEntity>,
    catalog: Map<String, ExerciseCatalogItem>,
): StrengthWorkoutSession {
    val items = sets
        .groupBy { it.exerciseId }
        .mapNotNull { (exerciseId, rows) ->
            val item = catalog[exerciseId] ?: return@mapNotNull null
            item to rows.sortedBy { it.setNumber }.map { it.toDomain() }
        }
    return StrengthWorkoutSession(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        note = note,
        difficulty = difficultyRaw?.let(DifficultyFeedback::fromRaw),
        items = items,
    )
}
