package com.silverbp.android.core.db

import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import java.time.Instant
import java.util.UUID

fun WeightReading.toEntity() = WeightReadingEntity(
    id = id.toString(),
    memberId = memberId,
    weightKg = weightKg,
    displayUnit = displayUnit.raw,
    timestamp = timestamp.toEpochMilli(),
    source = source.raw,
    note = note,
    photoFilename = photoFilename,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    hcRecordId = hcRecordId,
)

fun WeightReadingEntity.toDomain() = WeightReading(
    id = UUID.fromString(id),
    memberId = memberId,
    weightKg = weightKg,
    displayUnit = WeightUnit.fromRaw(displayUnit),
    timestamp = Instant.ofEpochMilli(timestamp),
    source = WeightSource.fromRaw(source),
    note = note,
    photoFilename = photoFilename,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    hcRecordId = hcRecordId,
)
