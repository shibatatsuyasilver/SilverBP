package com.silverbp.android.core.db

import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseSource
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.MeasureContext
import java.time.Instant
import java.util.UUID

fun GlucoseReading.toEntity() = GlucoseReadingEntity(
    id = id.toString(),
    memberId = memberId,
    valueMgdl = valueMgdl,
    displayUnit = displayUnit.raw,
    measureContext = measureContext.raw,
    timestamp = timestamp.toEpochMilli(),
    source = source.raw,
    confidence = confidence,
    note = note,
    photoFilename = photoFilename,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    hcRecordId = hcRecordId,
)

fun GlucoseReadingEntity.toDomain() = GlucoseReading(
    id = UUID.fromString(id),
    memberId = memberId,
    valueMgdl = valueMgdl,
    displayUnit = GlucoseUnit.fromRaw(displayUnit),
    measureContext = MeasureContext.fromRaw(measureContext),
    timestamp = Instant.ofEpochMilli(timestamp),
    source = GlucoseSource.fromRaw(source),
    confidence = confidence,
    note = note,
    photoFilename = photoFilename,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    hcRecordId = hcRecordId,
)
