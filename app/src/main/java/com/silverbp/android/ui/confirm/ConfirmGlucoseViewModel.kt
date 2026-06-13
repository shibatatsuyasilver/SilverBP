package com.silverbp.android.ui.confirm

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.R
import com.silverbp.android.capture.GlucoseCaptureSessionHolder
import com.silverbp.android.core.GlucoseCategory
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseRepository
import com.silverbp.android.core.GlucoseSource
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.MeasureContext
import com.silverbp.android.core.Member
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

private const val TAG = "ConfirmGlucose"

/** Per-member free-record cap before the Premium paywall (roadmap §4-6). */
private const val FREE_GLUCOSE_LIMIT = 10

/**
 * Backs ConfirmGlucoseScreen — the glucose analogue of [ConfirmReadingViewModel].
 * Editable value (with mg/dL ↔ mmol/L toggle), measure-context picker, date/time,
 * note, source, and per-reading member attribution.
 *
 * Carries the BP confirm bug-fix classes:
 *  - **M5** — the editable draft survives rotation AND process death via
 *    [SavedStateHandle]: every [update] mirrors the draft's primitive fields into
 *    the handle, and [initWith] restores from it before falling back to the nav
 *    arg. (A photo Bitmap can't survive process death, so glucose holds only the
 *    photo *filename* on disk — see [GlucoseDraft].)
 *  - **M6** — [save] is guarded by [saving]; the Save button is disabled while in
 *    flight so a double-tap can't persist twice.
 *  - **M8** — the catch inside the [viewModelScope] launch does NOT rethrow; it
 *    surfaces an inline error and keeps the draft so the user can retry, never
 *    crashing the screen.
 *
 * FREE-10 GATE: saving a NEW reading is blocked once the member already has
 * [FREE_GLUCOSE_LIMIT] readings and the user isn't Premium; [gateRequested] then
 * fires so the screen opens the paywall. Editing an existing reading is never
 * gated. With PREMIUM_ENFORCED=false (beta) isPremium() is true, so the gate is
 * effectively off and beta is unlimited — by design.
 */
class ConfirmGlucoseViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val repo: GlucoseRepository = ServiceLocator.glucoseRepository
    private val context: Context = ServiceLocator.context
    private val members: MemberRepository = ServiceLocator.memberRepository
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore

    private val classifier = GlucoseClassifier()

    private val _draft = MutableStateFlow(GlucoseDraft())
    val draft: StateFlow<GlucoseDraft> = _draft.asStateFlow()

    /** Active members for the in-screen attribution row (shown only when > 1). */
    val activeMembers: StateFlow<List<Member>> = members.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Non-null when the last save failed; cleared on retry. */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /**
     * Set to the category of the just-saved reading when it is hypoglycaemic
     * (VeryLow/Low) so the screen shows the immediate on-save warning card. The
     * screen clears it via [clearLowWarning] after acknowledging. Survives
     * rotation (StateFlow) but is intentionally transient (not in SavedStateHandle:
     * the row is already persisted, the warning is a one-shot acknowledgement).
     */
    private val _lowWarning = MutableStateFlow<GlucoseCategory?>(null)
    val lowWarning: StateFlow<GlucoseCategory?> = _lowWarning.asStateFlow()

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
     * Live classification of the current draft value (null while the value field is
     * empty/partial) — used to colour-tag the value and to decide the on-save warning.
     */
    val liveCategory: StateFlow<GlucoseCategory?> = _draft
        .map { categoryOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun categoryOf(d: GlucoseDraft): GlucoseCategory? =
        if (d.parsedValue == null) null else classifier.classify(d.valueMgdl, d.measureContext)

    /**
     * Initialise from a navigation argument:
     *  - "new"   → blank manual draft for the current member
     *  - "draft" → consume the OCR/camera draft staged in [GlucoseCaptureSessionHolder]
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
                    setDraft(GlucoseDraft(timestamp = Instant.now(), memberId = selectedMemberId))
                    editingId = null
                    savedState.remove<String>(KEY_EDITING_ID)
                }
                arg == "draft" -> {
                    val taken = GlucoseCaptureSessionHolder.take()
                    val base = taken ?: GlucoseDraft(timestamp = Instant.now(), source = GlucoseSource.Camera)
                    setDraft(if (base.memberId.isBlank()) base.copy(memberId = selectedMemberId) else base)
                    editingId = null
                    savedState.remove<String>(KEY_EDITING_ID)
                }
                else -> {
                    val id = runCatching { UUID.fromString(arg) }.getOrNull()
                    if (id != null) {
                        repo.findById(id)?.let {
                            setDraft(GlucoseDraft.fromReading(it))
                            editingId = it.id
                            savedState[KEY_EDITING_ID] = it.id.toString()
                        }
                    }
                }
            }
        }
    }

    fun update(transform: (GlucoseDraft) -> GlucoseDraft) {
        setDraft(transform(_draft.value))
    }

    /** Toggle the editing unit, converting the typed value so the number stays meaningful. */
    fun setUnit(unit: GlucoseUnit) {
        setDraft(_draft.value.convertedTo(unit))
    }

    /** Single sink for draft mutations that also mirrors to the handle (M5). */
    private fun setDraft(d: GlucoseDraft) {
        _draft.value = d
        mirrorToHandle(d)
    }

    fun clearLowWarning() { _lowWarning.value = null }

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
                "[ConfirmGlucose] save mgdl=${current.valueMgdl} unit=${current.displayUnit.raw} " +
                    "ctx=${current.measureContext.raw} src=${current.source.raw} conf=${current.confidence}",
            )
            try {
                // FREE-10 gate (new readings only). isPremium() is true in beta
                // (PREMIUM_ENFORCED=false) so this never blocks there.
                if (editingId == null && !ServiceLocator.entitlementManager.isPremium()) {
                    val memberId = current.memberId.ifBlank { repo.ownerId() }
                    if (repo.count(memberId) >= FREE_GLUCOSE_LIMIT) {
                        _saving.value = false
                        _gateRequested.value += 1
                        return@launch
                    }
                }

                val reading: GlucoseReading = if (editingId != null) {
                    // Carry the existing id + the (possibly reassigned) attribution.
                    current.toReading().copy(id = editingId!!, memberId = current.memberId)
                } else {
                    current.toReading()
                }
                repo.upsert(reading)

                // Low-glucose is the acute case — surface the immediate warning card
                // (roadmap §4-1). No background watcher (avoids M17 alert storms).
                val category = classifier.classify(reading.valueMgdl, reading.measureContext)
                _saving.value = false
                if (category.isHypoglycemic) {
                    _lowWarning.value = category
                    // Keep the screen open so the user reads the warning; the screen
                    // routes onDone after acknowledging.
                } else {
                    onDone()
                }
            } catch (e: Throwable) {
                // M8: never rethrow inside viewModelScope — keep the draft, show error.
                Log.e(TAG, "[ConfirmGlucose] save failed: ${e.message}", e)
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
                .onFailure { Log.e(TAG, "[ConfirmGlucose] delete failed: ${it.message}", it) }
            onDone()
        }
    }

    // ---- SavedStateHandle mirror (M5) ----

    private fun mirrorToHandle(d: GlucoseDraft) {
        savedState[KEY_VALUE_TEXT] = d.valueText
        savedState[KEY_UNIT] = d.displayUnit.raw
        savedState[KEY_CONTEXT] = d.measureContext.raw
        savedState[KEY_TIMESTAMP] = d.timestamp.toEpochMilli()
        savedState[KEY_SOURCE] = d.source.raw
        savedState[KEY_CONFIDENCE] = d.confidence
        savedState[KEY_NOTE] = d.note
        savedState[KEY_PHOTO] = d.photoFilename
        savedState[KEY_MEMBER_ID] = d.memberId
    }

    private fun restoreFromHandle(): GlucoseDraft = GlucoseDraft(
        valueText = savedState.get<String>(KEY_VALUE_TEXT) ?: "",
        displayUnit = GlucoseUnit.fromRaw(savedState.get<String>(KEY_UNIT) ?: GlucoseUnit.Mgdl.raw),
        measureContext = MeasureContext.fromRaw(savedState.get<String>(KEY_CONTEXT) ?: MeasureContext.Fasting.raw),
        timestamp = Instant.ofEpochMilli(savedState.get<Long>(KEY_TIMESTAMP) ?: System.currentTimeMillis()),
        source = GlucoseSource.fromRaw(savedState.get<String>(KEY_SOURCE) ?: GlucoseSource.Manual.raw),
        confidence = savedState.get<Double>(KEY_CONFIDENCE) ?: 1.0,
        note = savedState.get<String>(KEY_NOTE) ?: "",
        photoFilename = savedState.get<String>(KEY_PHOTO),
        memberId = savedState.get<String>(KEY_MEMBER_ID) ?: "",
    )

    private companion object {
        const val KEY_VALUE_TEXT = "g_value_text"
        const val KEY_UNIT = "g_unit"
        const val KEY_CONTEXT = "g_context"
        const val KEY_TIMESTAMP = "g_timestamp"
        const val KEY_SOURCE = "g_source"
        const val KEY_CONFIDENCE = "g_confidence"
        const val KEY_NOTE = "g_note"
        const val KEY_PHOTO = "g_photo"
        const val KEY_MEMBER_ID = "g_member_id"
        const val KEY_EDITING_ID = "g_editing_id"
    }
}
