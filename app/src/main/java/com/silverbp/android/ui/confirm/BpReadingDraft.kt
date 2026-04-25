package com.silverbp.android.ui.confirm

import android.graphics.Bitmap
import com.silverbp.android.core.Arm
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.core.Posture
import com.silverbp.android.core.Source
import java.time.Instant

/**
 * Mutable in-memory working copy of a reading. Mirrors iOS BPReadingDraft.
 * Photo is held in memory; persisted as JPEG to filesDir/photos/ on save.
 */
data class BpReadingDraft(
    val systolic: Int = 0,
    val diastolic: Int = 0,
    val pulse: Int? = null,
    val timestamp: Instant = Instant.now(),
    val arm: Arm = Arm.Left,
    val posture: Posture = Posture.Sitting,
    val partOfDay: PartOfDay = PartOfDay.Morning,
    val beforeMedication: Boolean = true,
    val irregularHeartbeat: Boolean = false,
    val confidence: Double = 1.0,
    val note: String = "",
    val photo: Bitmap? = null,
    val photoFilename: String? = null,
    val source: Source = Source.Manual,
) {
    val isValid: Boolean
        get() = systolic in 61..259 && diastolic in 31..159 && diastolic < systolic

    fun toReading(photoFilename: String? = null) = BpReading(
        systolic = systolic, diastolic = diastolic, pulse = pulse,
        timestamp = timestamp, arm = arm, posture = posture, partOfDay = partOfDay,
        beforeMedication = beforeMedication, irregularHeartbeat = irregularHeartbeat,
        confidence = confidence, note = note, photoFilename = photoFilename ?: this.photoFilename,
        source = source,
    )

    companion object {
        fun fromReading(r: BpReading) = BpReadingDraft(
            systolic = r.systolic, diastolic = r.diastolic, pulse = r.pulse,
            timestamp = r.timestamp, arm = r.arm, posture = r.posture, partOfDay = r.partOfDay,
            beforeMedication = r.beforeMedication, irregularHeartbeat = r.irregularHeartbeat,
            confidence = r.confidence, note = r.note, photoFilename = r.photoFilename,
            source = r.source,
        )
    }
}
