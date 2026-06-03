package com.silverbp.android.core.db

import com.silverbp.android.coach.CoachGoal
import com.silverbp.android.coach.CoachPlan
import com.silverbp.android.coach.CoachTask
import com.silverbp.android.coach.LifestyleModule
import com.silverbp.android.coach.Phase
import com.silverbp.android.coach.TaskIntensity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Coach domain ↔ persistence mappers. Goals are serialised as JSON inside
 * [CoachPlanEntity.goalsJson]; tasks live in their own table.
 */

private val coachJson = Json {
    ignoreUnknownKeys = true   // tolerant: schema evolution must not break loaders
    explicitNulls = false
}

fun CoachPlan.toPlanEntity(): CoachPlanEntity = CoachPlanEntity(
    id = id,
    weekStart = weekStartMillis,
    generatedAt = generatedAtMillis,
    ruleVersion = ruleVersion,
    phaseRaw = phase.raw,
    goalsJson = coachJson.encodeToString(ListSerializer(CoachGoal.serializer()), goals),
)

fun CoachTask.toTaskEntity(): CoachTaskEntity = CoachTaskEntity(
    id = id,
    planId = planId,
    dayOffset = dayOffset,
    moduleRaw = module.raw,
    title = title,
    targetValue = targetValue,
    targetUnit = targetUnit,
    intensityRaw = intensity.raw,
    safetyHold = safetyHold,
    completedAt = completedAtMillis,
    skipped = skipped,
    movedDayOffset = movedDayOffset,
)

fun CoachPlanEntity.toDomain(tasks: List<CoachTaskEntity>): CoachPlan = CoachPlan(
    id = id,
    weekStartMillis = weekStart,
    generatedAtMillis = generatedAt,
    ruleVersion = ruleVersion,
    phase = Phase.fromRaw(phaseRaw),
    goals = runCatching {
        coachJson.decodeFromString(ListSerializer(CoachGoal.serializer()), goalsJson)
    }.getOrDefault(emptyList()),
    tasks = tasks.map { it.toDomain() },
)

fun CoachTaskEntity.toDomain(): CoachTask = CoachTask(
    id = id,
    planId = planId,
    dayOffset = dayOffset,
    module = LifestyleModule.fromRaw(moduleRaw),
    title = title,
    targetValue = targetValue,
    targetUnit = targetUnit,
    intensity = TaskIntensity.fromRaw(intensityRaw),
    safetyHold = safetyHold,
    completedAtMillis = completedAt,
    skipped = skipped,
    movedDayOffset = movedDayOffset,
)
