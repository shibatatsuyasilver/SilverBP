package com.silverbp.android.core

import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class BpRepository(private val dao: BpDao) {

    fun observeLatest(): Flow<BpReading?> = dao.observeLatest().map { it?.toDomain() }

    fun observeAll(): Flow<List<BpReading>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeRange(from: Instant, to: Instant): Flow<List<BpReading>> =
        dao.observeRange(from.toEpochMilli(), to.toEpochMilli()).map { list -> list.map { it.toDomain() } }

    suspend fun findById(id: UUID): BpReading? = dao.findById(id.toString())?.toDomain()

    suspend fun upsert(reading: BpReading) {
        val now = Instant.now()
        val toSave = reading.copy(updatedAt = now)
        if (dao.findById(reading.id.toString()) == null) {
            dao.insert(toSave.copy(createdAt = now).toEntity())
        } else {
            dao.update(toSave.toEntity())
        }
    }

    suspend fun delete(id: UUID) = dao.delete(id.toString())

    suspend fun count(): Int = dao.count()
}
