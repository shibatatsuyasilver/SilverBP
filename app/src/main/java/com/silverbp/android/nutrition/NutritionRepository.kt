package com.silverbp.android.nutrition

import com.silverbp.android.core.db.DietCheckEntity
import com.silverbp.android.core.db.DietDao
import com.silverbp.android.core.db.FoodLogDao
import com.silverbp.android.core.db.FoodLogEntity
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.health.HealthConnectNutritionBridge
import com.silverbp.android.health.classifySodium
import com.silverbp.android.sync.engine.HlcClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Repository for logged meals. Flow reads, suspend writes, created/updated
 * stamping on upsert — same shape as [com.silverbp.android.core.BpRepository].
 *
 * On upsert it also (best-effort) mirrors to Health Connect and rolls today's
 * sodium into the Coach [DietCheckEntity], so high-sodium days surface in
 * coaching. Both side effects degrade silently when disabled/unavailable.
 */
class NutritionRepository(
    private val dao: FoodLogDao,
    private val dietDao: DietDao? = null,
    private val healthConnect: HealthConnectNutritionBridge? = null,
    /** Coarse on/off for the Health Connect mirror; defaults off for tests. */
    private val healthConnectEnabled: suspend () -> Boolean = { false },
    /** Stamps a monotonic HLC on each local write for cross-device LWW; null in tests. */
    private val clock: HlcClock? = null,
) {

    /** Stamp the current local-write HLC onto the entity (no-op when no clock). */
    private fun FoodLogEntity.stamped(): FoodLogEntity =
        clock?.let { copy(hlcUpdatedAt = it.next().packed) } ?: this

    fun observeAll(): Flow<List<FoodLog>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeRange(from: Instant, to: Instant): Flow<List<FoodLog>> =
        dao.observeRange(from.toEpochMilli(), to.toEpochMilli()).map { list -> list.map { it.toDomain() } }

    suspend fun findById(id: UUID): FoodLog? = dao.findById(id.toString())?.toDomain()

    suspend fun rangeOnce(from: Instant, to: Instant): List<FoodLog> =
        dao.rangeOnce(from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }

    suspend fun upsert(log: FoodLog) {
        val now = Instant.now()
        val existing = dao.findById(log.id.toString())
        var toSave = log.copy(
            updatedAt = now,
            createdAt = if (existing == null) now else log.createdAt,
            // Keep any prior HC mirror id the caller didn't carry so an edit
            // doesn't needlessly clear it and re-mirror.
            hcRecordId = log.hcRecordId ?: existing?.hcRecordId,
        )
        val foodEntity = toSave.toEntity().stamped()
        if (existing == null) dao.insert(foodEntity) else dao.update(foodEntity)

        // Best-effort one-way mirror to Health Connect (gated on the master
        // toggle; the bridge independently re-checks the write permission).
        if (healthConnect != null && runCatching { healthConnectEnabled() }.getOrDefault(false)) {
            val hcId = healthConnect.write(toSave)
            if (hcId != null && hcId != toSave.hcRecordId) {
                toSave = toSave.copy(hcRecordId = hcId, updatedAt = Instant.now())
                // hcRecordId is local-only (not synced) — preserve the HLC stamped above.
                dao.update(foodEntity.copy(hcRecordId = hcId, updatedAt = toSave.updatedAt.toEpochMilli()))
            }
        }

        updateDietRollup(toSave.timestamp)
    }

    suspend fun delete(id: UUID) {
        val existing = dao.findById(id.toString())
        dao.delete(id.toString())
        existing?.let { updateDietRollup(Instant.ofEpochMilli(it.timestamp)) }
    }

    suspend fun count(): Int = dao.count()

    /**
     * Recompute the given day's total logged sodium and write it into the Coach
     * [DietCheckEntity] as a coarse low/mid/high level (the category the Coach
     * engine consumes). Idempotent — recomputed from all of the day's logs, so
     * it never double-counts. Skips days the user set by hand in the Coach diet
     * log (sourceRaw == "manual") so it doesn't clobber explicit input.
     */
    private suspend fun updateDietRollup(forDay: Instant) {
        val dd = dietDao ?: return
        val zone = ZoneId.systemDefault()
        val dayStart = forDay.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val dayStartMs = dayStart.toEpochMilli()
        val dayEndMs = dayStart.plusSeconds(24L * 60 * 60).toEpochMilli()
        val existing = dd.forDay(dayStartMs)
        if (existing?.sourceRaw == "manual") return
        val logs = dao.rangeOnce(dayStartMs, dayEndMs)
        if (logs.isEmpty() && existing == null) return
        val totalMg = logs.sumOf { it.sodiumMg ?: 0.0 }
        dd.upsert(
            DietCheckEntity(
                dayStart = dayStartMs,
                sodiumLevelRaw = classifySodium(totalMg.toInt()),
                vegServings = existing?.vegServings ?: 0,
                sourceRaw = "food",
                updatedAt = System.currentTimeMillis(),
                hlcUpdatedAt = clock?.next()?.packed ?: (existing?.hlcUpdatedAt ?: "0"),
            ),
        )
    }
}
