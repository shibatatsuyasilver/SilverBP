package com.silverbp.android.coach

import com.silverbp.android.core.db.BpWorkoutAssociationDao
import com.silverbp.android.core.db.BpWorkoutAssociationEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Façade over [BpWorkoutAssociationDao]. Lives in the coach package so callers
 * never touch Room types directly — mirrors [CoachRepository].
 */
class BpWorkoutAssociationRepository(
    private val dao: BpWorkoutAssociationDao,
) {

    /**
     * Record a pre/post BP↔workout link. Returns the generated association id.
     *
     * @param sessionType "cardio" | "strength"
     * @param contextType "pre" | "post"
     */
    suspend fun addAssociation(
        bpReadingId: String,
        sessionId: String,
        sessionType: String,
        contextType: String,
    ): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            BpWorkoutAssociationEntity(
                id = id,
                bpReadingId = bpReadingId,
                sessionId = sessionId,
                sessionType = sessionType,
                contextType = contextType,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    fun observeForSession(sessionId: String): Flow<List<BpWorkoutAssociationEntity>> =
        dao.observeForSession(sessionId)

    suspend fun forSession(sessionId: String): List<BpWorkoutAssociationEntity> =
        dao.forSession(sessionId)
}
