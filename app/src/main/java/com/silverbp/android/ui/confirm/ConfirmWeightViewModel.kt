package com.silverbp.android.ui.confirm

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.R
import com.silverbp.android.core.BmiCalculator
import com.silverbp.android.core.BmiCategory
import com.silverbp.android.core.Member
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightRepository
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

private const val TAG = "ConfirmWeight"

/**
 * Backs ConfirmWeightScreen — the weight analogue of [ConfirmGlucoseViewModel].
 * Editable value (with kg ↔ lb toggle honouring [com.silverbp.android.settings.UserSettings.weightUnit]),
 * date/time, note, source (manual only this round), and per-reading member
 * attribution.
 *
 * Carries the BP/glucose confirm bug-fix classes:
 *  - **M5** — the editable draft survives rotation AND process death via
 *    [SavedStateHandle]: every [update] mirrors the draft's primitive fields into
 *    the handle, and [initWith] restores from it before falling back to the nav
 *    arg. (Weight is manual-only, so there is no Bitmap to lose — every field is a
 *    primitive, see [WeightDraft].)
 *  - **M6** — [save] is guarded by [saving]; the Save button is disabled while in
 *    flight so a double-tap can't persist twice.
 *  - **M8** — the catch inside the [viewModelScope] launch does NOT rethrow; it
 *    surfaces an inline error and keeps the draft so the user can retry, never
 *    crashing the screen.
 *
 * NO PREMIUM GATE: weight is owner-free and non-owner members are already gated by
 * family membership (roadmap §4-6 decision), so saving is never blocked here —
 * unlike the glucose free-10 gate.
 *
 * On a successful save, if the attributed member has a height set we compute the
 * BMI + Taiwan-standard category from the saved weight and surface it via
 * [savedBmi] so the screen can show it before returning. No height → null → the
 * screen returns immediately (it shows the "set height" hint elsewhere).
 */
class ConfirmWeightViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val repo: WeightRepository = ServiceLocator.weightRepository
    private val context: Context = ServiceLocator.context
    private val members: MemberRepository = ServiceLocator.memberRepository
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore
    private val settings: UserSettingsRepository = ServiceLocator.userSettings

    private val _draft = MutableStateFlow(WeightDraft())
    val draft: StateFlow<WeightDraft> = _draft.asStateFlow()

    /** Active members for the in-screen attribution row (shown only when > 1). */
    val activeMembers: StateFlow<List<Member>> = members.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Non-null when the last save failed; cleared on retry. */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /**
     * Set after a successful save when the attributed member has a height, so the
     * screen can show the resulting BMI + category before returning. Null means
     * "no height / not yet saved" — the screen returns immediately. The screen
     * clears it via [clearSavedBmi] after acknowledging.
     */
    private val _savedBmi = MutableStateFlow<SavedBmi?>(null)
    val savedBmi: StateFlow<SavedBmi?> = _savedBmi.asStateFlow()

    private var editingId: UUID? = null

    /** Guards [initWith] so activity recreation doesn't wipe the surviving draft. */
    private var initialized = false

    val isEditing: Boolean get() = editingId != null

    /**
     * Initialise from a navigation argument:
     *  - "new"   → blank manual draft for the current member, in the user's
     *              preferred [WeightUnit]
     *  - <uuid>  → load an existing reading for edit
     *
     * Idempotent and M5-aware: if the handle already holds a surviving draft (the
     * process was recreated), restore that instead of re-reading the nav arg.
     */
    fun initWith(arg: String?) {
        if (initialized) return
        initialized = true

        // M5: restore a process-death-surviving draft if one was mirrored.
        editingId = savedState.get<String>(KEY_EDITING_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (savedState.contains(KEY_VALUE_TEXT)) {
            _draft.value = restoreFromHandle()
            return
        }

        viewModelScope.launch {
            val selectedMemberId = currentMember.current()
            when {
                arg == null || arg == "new" -> {
                    val unit = WeightUnit.fromRaw(settings.flow.first().weightUnit)
                    setDraft(
                        WeightDraft(
                            timestamp = Instant.now(),
                            displayUnit = unit,
                            memberId = selectedMemberId,
                        ),
                    )
                    editingId = null
                    savedState.remove<String>(KEY_EDITING_ID)
                }
                else -> {
                    val id = runCatching { UUID.fromString(arg) }.getOrNull()
                    if (id != null) {
                        repo.findById(id)?.let {
                            setDraft(WeightDraft.fromReading(it))
                            editingId = it.id
                            savedState[KEY_EDITING_ID] = it.id.toString()
                        }
                    }
                }
            }
        }
    }

    fun update(transform: (WeightDraft) -> WeightDraft) {
        setDraft(transform(_draft.value))
    }

    /** Toggle the editing unit, converting the typed value so the number stays meaningful. */
    fun setUnit(unit: WeightUnit) {
        setDraft(_draft.value.convertedTo(unit))
    }

    /** Single sink for draft mutations that also mirrors to the handle (M5). */
    private fun setDraft(d: WeightDraft) {
        _draft.value = d
        mirrorToHandle(d)
    }

    fun clearSavedBmi() { _savedBmi.value = null }

    /**
     * Persist the draft. [onDone] runs only after a successful save. M6 guards
     * against double-taps; M8 keeps the catch from rethrowing. No premium gate —
     * weight is never blocked.
     *
     * After the local save we resolve the attributed member's height: if it is set
     * we surface the BMI via [savedBmi] (the screen shows it, then routes onDone on
     * acknowledge); otherwise we route onDone immediately.
     */
    fun save(onDone: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        _saveError.value = null
        viewModelScope.launch {
            val current = _draft.value
            Log.i(
                TAG,
                "[ConfirmWeight] save kg=${current.weightKg} unit=${current.displayUnit.raw} " +
                    "src=${current.source.raw}",
            )
            try {
                val reading: WeightReading = if (editingId != null) {
                    // Carry the existing id + the (possibly reassigned) attribution.
                    current.toReading().copy(id = editingId!!, memberId = current.memberId)
                } else {
                    current.toReading()
                }
                repo.upsert(reading)

                // Resolve the attributed member (empty → owner, exactly as the repo
                // resolved it on save) and compute BMI only when a height is set.
                val memberId = reading.memberId.ifBlank { repo.ownerId() }
                val height = runCatching {
                    UUID.fromString(memberId).let { members.findById(it)?.heightCm }
                }.getOrNull()

                _saving.value = false
                if (height != null && height > 0) {
                    _savedBmi.value = SavedBmi(
                        bmi = BmiCalculator.bmi(reading.weightKg, height),
                        category = BmiCalculator.category(reading.weightKg, height),
                    )
                    // Keep the screen open so the user sees the BMI; the screen
                    // routes onDone after acknowledging.
                } else {
                    onDone()
                }
            } catch (e: Throwable) {
                // M8: never rethrow inside viewModelScope — keep the draft, show error.
                Log.e(TAG, "[ConfirmWeight] save failed: ${e.message}", e)
                _saving.value = false
                _saveError.value = context.getString(
                    R.string.confirm_save_failed,
                    e.message ?: context.getString(R.string.err_unknown),
                )
            }
        }
    }

    /** Delete the reading being edited (no-op for a new/unsaved draft). */
    fun delete(onDone: () -> Unit) {
        val id = editingId ?: return onDone()
        viewModelScope.launch {
            runCatching { repo.delete(id) }
                .onFailure { Log.e(TAG, "[ConfirmWeight] delete failed: ${it.message}", it) }
            onDone()
        }
    }

    // ---- SavedStateHandle mirror (M5) ----

    private fun mirrorToHandle(d: WeightDraft) {
        savedState[KEY_VALUE_TEXT] = d.valueText
        savedState[KEY_UNIT] = d.displayUnit.raw
        savedState[KEY_TIMESTAMP] = d.timestamp.toEpochMilli()
        savedState[KEY_SOURCE] = d.source.raw
        savedState[KEY_NOTE] = d.note
        savedState[KEY_MEMBER_ID] = d.memberId
    }

    private fun restoreFromHandle(): WeightDraft = WeightDraft(
        valueText = savedState.get<String>(KEY_VALUE_TEXT) ?: "",
        displayUnit = WeightUnit.fromRaw(savedState.get<String>(KEY_UNIT) ?: WeightUnit.Kg.raw),
        timestamp = Instant.ofEpochMilli(savedState.get<Long>(KEY_TIMESTAMP) ?: System.currentTimeMillis()),
        source = WeightSource.fromRaw(savedState.get<String>(KEY_SOURCE) ?: WeightSource.Manual.raw),
        note = savedState.get<String>(KEY_NOTE) ?: "",
        memberId = savedState.get<String>(KEY_MEMBER_ID) ?: "",
    )

    /** Snapshot of the BMI to show after a successful save (member has a height). */
    data class SavedBmi(val bmi: Double, val category: BmiCategory)

    private companion object {
        const val KEY_VALUE_TEXT = "w_value_text"
        const val KEY_UNIT = "w_unit"
        const val KEY_TIMESTAMP = "w_timestamp"
        const val KEY_SOURCE = "w_source"
        const val KEY_NOTE = "w_note"
        const val KEY_MEMBER_ID = "w_member_id"
        const val KEY_EDITING_ID = "w_editing_id"
    }
}
