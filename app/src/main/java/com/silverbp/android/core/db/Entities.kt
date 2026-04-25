package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bp_reading", indices = [Index("timestamp")])
data class BpReadingEntity(
    @PrimaryKey val id: String,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val timestamp: Long,
    val arm: String,
    val posture: String,
    val partOfDay: String,
    val beforeMedication: Boolean,
    val photoFilename: String?,
    val confidence: Double,
    val source: String,
    val note: String,
    val irregularHeartbeat: Boolean,
    val medicationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val birthYear: Int,
    val hasDiabetes: Boolean,
    val hasCKD: Boolean,
    val hasASCVD: Boolean,
    val guideline: String,
)

@Entity(tableName = "medication")
data class MedicationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val dose: String,
)

@Entity(tableName = "tag")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "reading_tag", primaryKeys = ["readingId", "tagId"])
data class ReadingTagCrossRef(
    val readingId: String,
    val tagId: String,
)
