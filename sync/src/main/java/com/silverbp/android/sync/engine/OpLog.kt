package com.silverbp.android.sync.engine

/**
 * Append-only log of [SyncRecord] operations the local device has either
 * produced (writes) or received (peer pushes). The merger consumes this log
 * to update Room; the outbox uses it to drive offline → online catch-up.
 *
 * Phase 1 stub — the full implementation lands in Phase 1.2 alongside the
 * Bonjour transport.
 */
interface OpLog {
    suspend fun append(record: SyncRecord)
    suspend fun recordsSince(hlc: Hlc, limit: Int): List<SyncRecord>
}
