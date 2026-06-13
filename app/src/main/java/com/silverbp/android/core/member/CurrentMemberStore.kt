package com.silverbp.android.core.member

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Deliberately a SEPARATE DataStore from "user_settings": the selected member is
// device-local state and is intentionally NOT carried in settings sync — two
// paired devices each pick their own current member.
private val Context.currentMemberDataStore by preferencesDataStore(name = "current_member")

/**
 * Persists which member is currently selected across the app (survives process
 * death — Today/Data scope their queries to it). Persisted rather than session-
 * scoped because OEM task-killing on elder phones would otherwise reset the
 * selection mid-use; mirrors the DataStore pattern in
 * [com.silverbp.android.settings.UserSettingsRepository].
 *
 * The [flow] falls back to the owner id whenever the stored value is blank /
 * missing / points at an archived-or-deleted member, so callers always observe
 * a valid member id without special-casing the empty state.
 *
 * [flow] re-resolves not only when the DataStore selection changes but also when
 * the active-member set changes (combined with [MemberRepository.observeActive]).
 * Archiving/deleting the currently-selected member writes the Room `member`
 * table, not this DataStore, so without that combine a flatMapLatest subscriber
 * (Today/History/Insights/Report) would stay pinned to the now-hidden member's
 * data while the switcher chip silently fell back to the owner. Recombining on
 * the member set re-runs [resolve] the instant the selection leaves the active
 * set, so the data screens fall back to the owner immediately.
 */
class CurrentMemberStore(
    private val context: Context,
    private val members: MemberRepository,
) {
    private object Keys {
        val CURRENT_MEMBER_ID = stringPreferencesKey("current_member_id")
    }

    /** Emits the selected member id, falling back to the owner when unset/invalid. */
    val flow: Flow<String> = combine(
        context.currentMemberDataStore.data.map { prefs -> prefs[Keys.CURRENT_MEMBER_ID] },
        // Recompute whenever a member is archived/unarchived/deleted so the
        // resolution below reacts to the selection leaving the active set.
        members.observeActive(),
    ) { stored, active ->
        resolve(stored, active.map { it.id.toString() })
    }.distinctUntilChanged()

    /** One-shot read of the resolved current member id. */
    suspend fun current(): String = flow.first()

    suspend fun setCurrent(memberId: String) {
        context.currentMemberDataStore.edit { it[Keys.CURRENT_MEMBER_ID] = memberId }
    }

    /** Clear the selection → [flow] resolves back to the owner. */
    suspend fun clear() {
        context.currentMemberDataStore.edit { it.remove(Keys.CURRENT_MEMBER_ID) }
    }

    /**
     * Resolve [stored] against the live [activeIds] (already excludes archived /
     * deleted members). An id that has left the active set — archived, deleted,
     * or never existed — falls back to the owner so the UI is never stranded on a
     * member with no switcher entry.
     */
    private suspend fun resolve(stored: String?, activeIds: List<String>): String {
        if (stored.isNullOrBlank()) return members.ownerId()
        return if (stored in activeIds) stored else members.ownerId()
    }
}
