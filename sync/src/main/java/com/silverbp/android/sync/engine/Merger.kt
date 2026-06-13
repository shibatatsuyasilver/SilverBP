package com.silverbp.android.sync.engine

import com.silverbp.android.sync.protocol.SyncRecordSink

/**
 * Applies an incoming [SyncRecord] to the local Room store using LWW rules:
 *  - Live record:  apply iff `record.hlc > local.hlcUpdatedAt`.
 *  - Tombstone:    delete iff `record.hlc > local.hlcUpdatedAt(or tombstone.hlc)`.
 *  - reading_tag:  add-wins set semantics (out-of-band — handled by the tag
 *    mapper, not gated here).
 *
 * The concrete gate is [LwwMerger]; the app wires its [LwwMerger.localHlc]
 * lookup against the per-table `hlcUpdatedAt` column + any tombstone hlc.
 */
interface Merger {
    /** Returns true iff the record was applied (i.e. its HLC dominated local). */
    suspend fun apply(record: SyncRecord): Boolean
}

/**
 * Last-Writer-Wins gate over an underlying [SyncRecordSink]. Decides whether an
 * inbound [SyncRecord] dominates the local copy and only then delegates the
 * actual write to [inner]. This is the missing B6 gate: without it, every peer
 * round blindly REPLACEs local rows, so two devices end each round holding the
 * other's copy and stale tombstones resurrect-then-delete newer edits.
 *
 * The HLC is a total order (physical → logical → nodeId, see [Hlc]); equal HLCs
 * are byte-identical states, so the tie rule is **stale-on-equal** (`>` not
 * `>=`): an equal-HLC record carries the same content we already hold and need
 * not be re-applied. The 64-bit random nodeId makes a genuine cross-device tie
 * at the same (physical, logical) astronomically unlikely; if it ever happens
 * the two records are byte-equal anyway.
 *
 * @param localHlc returns the local high-water HLC for [record]'s identity —
 *   the greater of the live row's `hlcUpdatedAt` and any tombstone hlc for that
 *   (entityType, pk). Null means "no local trace" → the record always applies.
 */
class LwwMerger(
    private val inner: SyncRecordSink,
    private val localHlc: suspend (SyncRecord) -> Hlc?,
) : Merger {

    override suspend fun apply(record: SyncRecord): Boolean {
        val local = localHlc(record)
        if (local != null && record.hlc <= local) {
            // Stale (or equal) — local copy already dominates. Drop it. This is
            // the single guard that covers both live overwrites and tombstone
            // deletes: a stale tombstone can't delete a newer live row, and a
            // stale live record can't overwrite a newer local edit.
            return false
        }
        inner.apply(record)
        return true
    }
}
