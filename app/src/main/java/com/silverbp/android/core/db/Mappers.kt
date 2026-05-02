package com.silverbp.android.core.db

import com.silverbp.android.core.Arm
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.Medication
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.core.Posture
import com.silverbp.android.core.Source
import com.silverbp.android.core.Tag
import com.silverbp.android.core.UserProfile
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.exercise.ExerciseSource
import com.silverbp.android.exercise.RoutePoint
import java.time.Instant
import java.util.UUID

fun BpReading.toEntity() = BpReadingEntity(
    id = id.toString(),
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    timestamp = timestamp.toEpochMilli(),
    arm = arm.raw,
    posture = posture.raw,
    partOfDay = partOfDay.raw,
    beforeMedication = beforeMedication,
    photoFilename = photoFilename,
    confidence = confidence,
    source = source.raw,
    note = note,
    irregularHeartbeat = irregularHeartbeat,
    medicationId = medicationId?.toString(),
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

fun BpReadingEntity.toDomain() = BpReading(
    id = UUID.fromString(id),
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    timestamp = Instant.ofEpochMilli(timestamp),
    arm = Arm.fromRaw(arm),
    posture = Posture.fromRaw(posture),
    partOfDay = PartOfDay.fromRaw(partOfDay),
    beforeMedication = beforeMedication,
    photoFilename = photoFilename,
    confidence = confidence,
    source = Source.fromRaw(source),
    note = note,
    irregularHeartbeat = irregularHeartbeat,
    medicationId = medicationId?.let(UUID::fromString),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

fun UserProfile.toEntity() = UserProfileEntity(
    id = id.toString(), displayName = displayName, birthYear = birthYear,
    hasDiabetes = hasDiabetes, hasCKD = hasCKD, hasASCVD = hasASCVD,
    guideline = guideline.raw,
)

fun UserProfileEntity.toDomain() = UserProfile(
    id = UUID.fromString(id), displayName = displayName, birthYear = birthYear,
    hasDiabetes = hasDiabetes, hasCKD = hasCKD, hasASCVD = hasASCVD,
    guideline = HypertensionGuideline.fromRaw(guideline),
)

fun Medication.toEntity() = MedicationEntity(id = id.toString(), name = name, dose = dose)
fun MedicationEntity.toDomain() = Medication(id = UUID.fromString(id), name = name, dose = dose)
fun Tag.toEntity() = TagEntity(id = id.toString(), name = name)
fun TagEntity.toDomain() = Tag(id = UUID.fromString(id), name = name)

fun ExerciseSession.toEntity() = ExerciseSessionEntity(
    id = id.toString(),
    activityKind = kind.raw,
    startedAt = startedAt.toEpochMilli(),
    endedAt = endedAt.toEpochMilli(),
    distanceMeters = distanceMeters,
    stepCount = stepCount,
    averagePaceSecPerKm = averagePaceSecPerKm,
    source = source.raw,
    note = note,
    hcRecordId = hcRecordId,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

fun ExerciseSessionEntity.toDomain() = ExerciseSession(
    id = UUID.fromString(id),
    kind = ActivityKind.fromRaw(activityKind),
    startedAt = Instant.ofEpochMilli(startedAt),
    endedAt = Instant.ofEpochMilli(endedAt),
    distanceMeters = distanceMeters,
    stepCount = stepCount,
    averagePaceSecPerKm = averagePaceSecPerKm,
    source = ExerciseSource.fromRaw(source),
    note = note,
    hcRecordId = hcRecordId,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

fun RoutePoint.toEntity() = RoutePointEntity(
    id = id.toString(),
    sessionId = sessionId.toString(),
    timestamp = timestamp.toEpochMilli(),
    lat = lat,
    lon = lon,
    horizontalAccuracy = horizontalAccuracy,
    altitude = altitude,
    speedMps = speedMps,
)

fun RoutePointEntity.toDomain() = RoutePoint(
    id = UUID.fromString(id),
    sessionId = UUID.fromString(sessionId),
    timestamp = Instant.ofEpochMilli(timestamp),
    lat = lat,
    lon = lon,
    horizontalAccuracy = horizontalAccuracy,
    altitude = altitude,
    speedMps = speedMps,
)
