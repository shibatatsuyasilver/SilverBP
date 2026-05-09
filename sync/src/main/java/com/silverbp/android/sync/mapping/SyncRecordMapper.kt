package com.silverbp.android.sync.mapping

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncRecord

/**
 * Bidirectional mapper between Room entity rows (`:app` module) and
 * wire-format [SyncRecord]s. The actual mapper depends on Room entities
 * defined in `:app`, so the concrete implementation lives there as
 * `:app:.../sync/SyncBridge.kt`. This module exposes the protocol seam.
 *
 * Phase 1 covers `BPReading` only; remaining entities land in Phase 2.
 *
 * ### Wire field tags (BPReading payload, integer-keyed CBOR map) — must match
 * `Packages/BPSharing/Sources/BPSharing/Mapping/SwiftDataMapper.swift`:
 *
 *   1: systolic        (Int)
 *   2: diastolic       (Int)
 *   3: pulse           (Int?)
 *   4: timestampMs     (Long)
 *   5: armRaw          (String)
 *   6: postureRaw      (String)
 *   7: partOfDayRaw    (String)
 *   8: beforeMedication (Bool)
 *   9: photoFilename   (String?)
 *  10: confidence      (Double)
 *  11: sourceRaw       (String)
 *  12: note            (String)
 *  13: irregularHeartbeat (Bool)
 *  14: medicationId    (String?)
 *  15: createdAtMs     (Long)
 *  16: updatedAtMs     (Long)
 */
interface SyncRecordMapper<TEntity> {
    fun encode(entity: TEntity, hlc: Hlc): SyncRecord
    suspend fun apply(record: SyncRecord)
}
