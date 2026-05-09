package com.silverbp.android.sync.engine

/**
 * Applies an incoming [SyncRecord] to the local Room store using LWW rules:
 *  - Live record:  apply iff `record.hlc > local.hlcUpdatedAt`.
 *  - Tombstone:    delete iff `record.hlc > local.hlcUpdatedAt(or tombstone.hlc)`.
 *  - reading_tag:  add-wins set semantics (out-of-band — see Phase 2).
 *
 * Phase 1 stub — concrete merger in Phase 1.2 alongside `SyncRecordMapper`.
 */
interface Merger {
    /** Returns true iff the record was applied (i.e. its HLC dominated local). */
    suspend fun apply(record: SyncRecord): Boolean
}
