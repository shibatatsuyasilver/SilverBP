package com.silverbp.android.sync.engine

/**
 * Thrown by a sink mapper when an inbound record can't be applied yet because a
 * record it depends on (its foreign-key parent) hasn't arrived — e.g. a
 * `route_point` before its `exercise_session`, a `set_log` before its strength
 * session, or a `coach_task` before its `coach_plan`.
 *
 * The sync session treats this as RETRY-LATER, not as applied: it must NOT
 * observe the record's HLC or let the peer watermark advance past it. Otherwise
 * the peer would never re-send the child and it would be lost forever (QA
 * P1-18 / 2026-06-19 re-audit finding #5). The child is re-shipped on a later
 * round once its parent has landed, and the LWW gate dedupes the rows that did
 * apply this round.
 *
 * Deliberately extends [Exception] (not `CancellationException`) so it never
 * interferes with coroutine cancellation.
 */
class OrphanRecordException(message: String) : Exception(message)
