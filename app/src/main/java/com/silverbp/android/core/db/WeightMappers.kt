package com.silverbp.android.core.db

import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import java.time.Instant
import java.util.UUID

fun WeightReading.toEntity() = WeightLogEntity(
    id = id.toString(),
    memberId = memberId,
    valueKg = valueKg,
    displayUnit = displayUnit.raw,
    timestamp = timestamp.toEpochMilli(),
    source = source.raw,
    confidence = confidence,
    note = note,
    photoFilename = photoFilename,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    hcRecordId = hcRecordId,
)

fun WeightLogEntity.toDomain() = WeightReading(
    id = UUID.fromString(id),
    memberId = memberId,
    valueKg = valueKg,
    displayUnit = WeightUnit.fromRaw(displayUnit),
    timestamp = Instant.ofEpochMilli(timestamp),
    source = WeightSource.fromRaw(source),
    confidence = confidence,
    note = note,
    photoFilename = photoFilename,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    hcRecordId = hcRecordId,
)
