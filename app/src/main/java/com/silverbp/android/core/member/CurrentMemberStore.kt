package com.silverbp.android.core.member

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
 */
class CurrentMemberStore(
    private val context: Context,
    private val members: MemberRepository,
) {
    private object Keys {
        val CURRENT_MEMBER_ID = stringPreferencesKey("current_member_id")
    }

    /** Emits the selected member id, falling back to the owner when unset/invalid. */
    val flow: Flow<String> = context.currentMemberDataStore.data.map { prefs ->
        resolve(prefs[Keys.CURRENT_MEMBER_ID])
    }

    /** One-shot read of the resolved current member id. */
    suspend fun current(): String = flow.first()

    suspend fun setCurrent(memberId: String) {
        context.currentMemberDataStore.edit { it[Keys.CURRENT_MEMBER_ID] = memberId }
    }

    /** Clear the selection → [flow] resolves back to the owner. */
    suspend fun clear() {
        context.currentMemberDataStore.edit { it.remove(Keys.CURRENT_MEMBER_ID) }
    }

    private suspend fun resolve(stored: String?): String {
        if (stored.isNullOrBlank()) return members.ownerId()
        // A member that was archived/deleted shouldn't strand the UI on a member
        // with no switcher entry — fall back to the owner.
        val existing = runCatching { members.findById(java.util.UUID.fromString(stored)) }.getOrNull()
        return if (existing != null && !existing.archived) stored else members.ownerId()
    }
}
