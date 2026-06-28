package com.silverbp.android.sync

import android.content.Context
import com.silverbp.android.sync.engine.Hlc

/**
 * Durable high-water store for the device's [com.silverbp.android.sync.engine.HlcClock].
 *
 * Persisting the last issued / observed HLC and re-seeding the clock from it on
 * cold start stops the clock from ever going backwards after a process restart
 * or wall-clock skew. A backwards HLC would let a stale local edit incorrectly
 * win (or lose) the LWW gate and silently corrupt multi-device data — the
 * highest-risk gap that kept LAN sync hidden (QA P0-4, audit finding #4).
 *
 * The HLC is not secret, so plain prefs are fine; writes use `apply()` so the
 * frequent per-tick saves coalesce off the main thread.
 */
class HlcClockStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The cold-start seed for the clock: the persisted high-water, or null if
     * the clock has never run on this device.
     *
     * The DB's own high-water (`MAX(hlc)` over the tombstone table) is folded in
     * separately at startup via [com.silverbp.android.sync.engine.HlcClock.observe]
     * on a background thread — see `SilverBpApplication.reconcileSync()` — so the
     * clock can't regress below durable DB state after a prefs-losing restore
     * (clear-data, or a .sbpbk/system restore that omits these prefs), which would
     * otherwise let it re-issue an HLC below one a peer already observed and
     * silently flip the LWW gate (QA P0-4).
     */
    fun load(): Hlc? = prefs.getString(KEY_LAST_SEEN, null)
        ?.let { runCatching { Hlc(it) }.getOrNull() }

    /** Record [hlc] as the new high-water. Safe to call on every clock tick. */
    fun save(hlc: Hlc) {
        prefs.edit().putString(KEY_LAST_SEEN, hlc.packed).apply()
    }

    private companion object {
        const val PREFS = "silverbp_hlc_clock"
        const val KEY_LAST_SEEN = "last_seen_hlc"
    }
}
