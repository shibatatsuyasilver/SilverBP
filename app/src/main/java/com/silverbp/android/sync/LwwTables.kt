package com.silverbp.android.sync

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType

/**
 * Fixed allowlist mapping each LWW-gated [SyncEntityType] to its Room table and
 * primary-key column. Used by [CombinedRoomSyncSink] to read the local row's
 * `hlcUpdatedAt` for the B6 gate. The values are compile-time constants — never
 * derived from wire input — so the raw `SELECT hlcUpdatedAt FROM <table> WHERE
 * <pkColumn> = ?` in [com.silverbp.android.core.db.SyncDao.localRowHlc] can't be
 * weaponised by a peer.
 *
 * Membership rule: a type is gated here iff its table carries an `hlcUpdatedAt`
 * column AND the wire `pk` equals the local primary key for that table.
 *
 * Deliberately ABSENT:
 *  - `chat_session` / `chat_message` — no `hlcUpdatedAt` column (backup-only,
 *    overwrite-wins by design); never reach the LAN sink.
 *  - `settings_kv` — DataStore KV, merged by its own mapper, not a Room row.
 *  - `reading_tag` — add-wins set membership, applied out-of-band (Phase 2).
 *  - `medication_dose` — its mapper dedups by content tuple, not pk, so the
 *    wire pk needn't match the local id; the dose mapper does its own hlc check
 *    against the content-matched row instead of this pk-keyed lookup.
 *  - `route_point` — child of exercise_session; not independently editable, no
 *    tombstones, append-only on the receiver.
 */
internal object LwwTables {

    /** (tableName, pkColumn) for each gated type, or null if not pk-gated. */
    fun pkColumnFor(type: SyncEntityType): Pair<String, String>? = when (type) {
        SyncEntityType.BP_READING -> "bp_reading" to "id"
        SyncEntityType.MEDICATION -> "medication" to "id"
        SyncEntityType.MEDICATION_SCHEDULE -> "medication_schedule" to "id"
        SyncEntityType.EXERCISE_SESSION -> "exercise_session" to "id"
        SyncEntityType.DAILY_STEP_LOG -> "daily_step_log" to "dayStart"
        SyncEntityType.ACHIEVEMENT -> "achievement" to "kindRaw"
        SyncEntityType.COACH_PLAN -> "coach_plan" to "id"
        SyncEntityType.COACH_TASK -> "coach_task" to "id"
        SyncEntityType.SLEEP_LOG -> "sleep_log" to "dayStart"
        SyncEntityType.DIET_CHECK -> "diet_check" to "dayStart"
        SyncEntityType.EXERCISE_CATALOG_ITEM -> "exercise_catalog_item" to "id"
        SyncEntityType.STRENGTH_WORKOUT_SESSION -> "strength_workout_session" to "id"
        SyncEntityType.SET_LOG -> "set_log" to "id"
        SyncEntityType.BP_WORKOUT_ASSOCIATION -> "bp_workout_association" to "id"
        SyncEntityType.FOOD_LOG -> "food_log" to "id"
        SyncEntityType.MEMBER -> "member" to "id"
        // Not pk-gated (see object KDoc): pass through to the mapper's own
        // apply semantics.
        else -> null
    }

    /**
     * Folds the live row's `hlcUpdatedAt` ([live]) and any tombstone hlc
     * ([tombstone]) into the single local high-water HLC the LWW gate compares
     * against. A pre-sync sentinel ("0" or all-zeros) is treated as "no local
     * trace" — it loses to any real inbound HLC, matching how the source side
     * mints a fresh HLC for such rows. Returns null when there's no real local
     * HLC, in which case the gate always applies the incoming record.
     */
    fun resolveLocalHlc(live: String?, tombstone: String?): Hlc? {
        val best = listOfNotNull(live, tombstone).maxOrNull() ?: return null
        if (best == "0" || best == "0".repeat(Hlc.HEX_LEN)) return null
        return Hlc(best)
    }
}
