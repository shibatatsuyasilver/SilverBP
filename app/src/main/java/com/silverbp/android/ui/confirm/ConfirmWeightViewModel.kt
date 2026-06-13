package com.silverbp.android.ui.confirm

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.R
import com.silverbp.android.core.Member
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightRepository
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

private const val TAG = "ConfirmWeight"

/** Per-member free-record cap before the Premium paywall (roadmap §4-6). */
private const val FREE_WEIGHT_LIMIT = 10

/** Plausible human body-weight bounds in canonical kg; saves outside are rejected. */
private const val MIN_WEIGHT_KG = 20.0
private const val MAX_WEIGHT_KG = 300.0

/**
 * Backs ConfirmWeightScreen — the weight analogue of [ConfirmGlucoseViewModel].
 * Editable value (with kg ↔ lb toggle), date/time, note, source, and per-reading
 * member attribution. This phase is manual entry only (no camera/photo), so there
 * is no capture-session draft to consume and no acute on-save warning — weight has
 * no hypoglycaemia-style immediate case (the BMI band is shown on Insights, not here).
 *
 * Carries the BP/glucose confirm bug-fix classes:
 *  - **M5** — the editable draft survives rotation AND process death via
 *    [SavedStateHandle]: every [update] mirrors the draft's primitive fields into
 *    the handle, and [initWith] restores from it before falling back to the nav arg.
 *  - **M6** — [save] is guarded by [saving]; the Save button is disabled while in
 *    flight so a double-tap can't persist twice.
 *  - **M8** — the catch inside the [viewModelScope] launch does NOT rethrow; it
 *    surfaces an inline error and keeps the draft so the user can retry, never
 *    crashing the screen.
 *
 * FREE-10 GATE: saving a NEW reading is blocked once the member already has
 * [FREE_WEIGHT_LIMIT] readings and the user isn't Premium; [gateRequested] then
 * fires so the screen opens the paywall. Editing an existing reading is never
 * gated. With PREMIUM_ENFORCED=false (beta) isPremium() is true, so the gate is
 * effectively off and beta is unlimited — by design.
 */
class ConfirmWeightViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val repo: WeightRepository = ServiceLocator.weightRepository
    private val context: Context = ServiceLocator.context
    private val members: MemberRepository = ServiceLocator.memberRepository
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore

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
     * One-shot signal that the free-tier gate blocked this save — the screen opens
     * the paywall. Incrementing counter so repeated taps re-trigger; the screen
     * acknowledges via [consumeGate].
     */
    private val _gateRequested = MutableStateFlow(0)
    val gateRequested: StateFlow<Int> = _gateRequested.asStateFlow()

    private var editingId: UUID? = null

    /** Guards [initWith] so activity recreation doesn't wipe the surviving draft. */
    private var initialized = false

    val isEditing: Boolean get() = editingId != null

    /**
     * Initialise from a navigation argument:
     *  - "new"   → blank manual draft for the current member
     *  - "draft" → blank manual draft (the camera/OCR capture path is a later phase,
     *              so there is no staged draft to consume yet; treated like "new"
     *              but tagged as the draft entry point for forward compatibility)
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
                arg == null || arg == "new" || arg == "draft" -> {
                    setDraft(WeightDraft(timestamp = Instant.now(), memberId = selectedMemberId))
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

    fun consumeGate() { /* reset is implicit: the screen reads the counter */ }

    /**
     * Persist the draft. [onDone] runs only after a successful save. M6 guards
     * against double-taps; M8 keeps the catch from rethrowing.
     *
     * For a NEW reading we first apply the free-10 gate: if the member is at/over
     * the cap and the user isn't Premium, we fire [gateRequested] (the screen opens
     * the paywall) and do NOT save. Editing an existing reading skips the gate.
     */
    fun save(onDone: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        _saveError.value = null
        viewModelScope.launch {
            val current = _draft.value
            Log.i(
                TAG,
                "[ConfirmWeight] save kg=${current.valueKg} unit=${current.displayUnit.raw} " +
                    "src=${current.source.raw} conf=${current.confidence}",
            )
            try {
                // Reject values outside the plausible body-weight band so a typo
                // (or a bad capture in a later phase) can't persist a nonsense kg.
                if (current.valueKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) {
                    _saving.value = false
                    _saveError.value = context.getString(
                        R.string.confirm_save_failed,
                        context.getString(R.string.err_unknown),
                    )
                    return@launch
                }

                // FREE-10 gate (new readings only). isPremium() is true in beta
                // (PREMIUM_ENFORCED=false) so this never blocks there.
                if (editingId == null && !ServiceLocator.entitlementManager.isPremium()) {
                    val memberId = current.memberId.ifBlank { repo.ownerId() }
                    if (repo.count(memberId) >= FREE_WEIGHT_LIMIT) {
                        _saving.value = false
                        _gateRequested.value += 1
                        return@launch
                    }
                }

                val reading: WeightReading = if (editingId != null) {
                    // Carry the existing id + the (possibly reassigned) attribution.
                    current.toReading().copy(id = editingId!!, memberId = current.memberId)
                } else {
                    current.toReading()
                }
                repo.upsert(reading)
                _saving.value = false
                onDone()
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
        savedState[KEY_CONFIDENCE] = d.confidence
        savedState[KEY_NOTE] = d.note
        savedState[KEY_PHOTO] = d.photoFilename
        savedState[KEY_MEMBER_ID] = d.memberId
    }

    private fun restoreFromHandle(): WeightDraft = WeightDraft(
        valueText = savedState.get<String>(KEY_VALUE_TEXT) ?: "",
        displayUnit = WeightUnit.fromRaw(savedState.get<String>(KEY_UNIT) ?: WeightUnit.Kg.raw),
        timestamp = Instant.ofEpochMilli(savedState.get<Long>(KEY_TIMESTAMP) ?: System.currentTimeMillis()),
        source = WeightSource.fromRaw(savedState.get<String>(KEY_SOURCE) ?: WeightSource.Manual.raw),
        confidence = savedState.get<Double>(KEY_CONFIDENCE) ?: 1.0,
        note = savedState.get<String>(KEY_NOTE) ?: "",
        photoFilename = savedState.get<String>(KEY_PHOTO),
        memberId = savedState.get<String>(KEY_MEMBER_ID) ?: "",
    )

    private companion object {
        const val KEY_VALUE_TEXT = "w_value_text"
        const val KEY_UNIT = "w_unit"
        const val KEY_TIMESTAMP = "w_timestamp"
        const val KEY_SOURCE = "w_source"
        const val KEY_CONFIDENCE = "w_confidence"
        const val KEY_NOTE = "w_note"
        const val KEY_PHOTO = "w_photo"
        const val KEY_MEMBER_ID = "w_member_id"
        const val KEY_EDITING_ID = "w_editing_id"
    }
}
