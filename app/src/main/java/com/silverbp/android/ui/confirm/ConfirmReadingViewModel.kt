package com.silverbp.android.ui.confirm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.R
import com.silverbp.android.capture.CaptureSessionHolder
import com.silverbp.android.core.Arm
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.core.Member
import com.silverbp.android.core.Posture
import com.silverbp.android.core.Source
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

private const val TAG = "ConfirmReading"

class ConfirmReadingViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val repo: BpRepository = ServiceLocator.bpRepository
    private val context: Context = ServiceLocator.context
    private val members: MemberRepository = ServiceLocator.memberRepository
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore

    private val _draft = MutableStateFlow(BpReadingDraft())
    val draft: StateFlow<BpReadingDraft> = _draft.asStateFlow()

    /**
     * Active members for the in-screen attribution row. The row is shown only
     * when there is more than one — single-member installs never see it.
     */
    val activeMembers: StateFlow<List<Member>> = members.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Non-null when the last save failed; cleared on retry. */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /**
     * Set after saving a crisis-range reading so the screen shows a one-shot,
     * non-diagnostic acknowledgement before returning.
     */
    private val _crisisWarning = MutableStateFlow(false)
    val crisisWarning: StateFlow<Boolean> = _crisisWarning.asStateFlow()

    private var editingId: UUID? = null

    /** Guards [initWith] so activity recreation doesn't wipe the surviving draft. */
    private var initialized = false

    /** True when this screen is editing an existing reading (vs. confirming a new one). */
    val isEditing: Boolean get() = editingId != null

    /**
     * Initialise from a navigation argument:
     *  - "new"   → blank manual draft
     *  - "draft" → consume current capture-session draft (with photo + AI confidence)
     *  - <uuid>  → load existing reading for edit
     *
     * Idempotent: re-invocations (e.g. LaunchedEffect re-running after rotation)
     * are no-ops so the surviving ViewModel draft is not overwritten.
     */
    fun initWith(arg: String?) {
        if (initialized) return
        initialized = true

        // M5: restore a process-death-surviving draft if one was mirrored.
        editingId = savedState.get<String>(KEY_EDITING_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (savedState.contains(KEY_SYS)) {
            _draft.value = restoreFromHandle()
            return
        }

        viewModelScope.launch {
            // New/draft readings default to the currently-selected member; editing
            // an existing reading keeps its own memberId (carried in fromReading).
            val selectedMemberId = currentMember.current()
            when {
                arg == null || arg == "new" -> {
                    setDraft(BpReadingDraft(timestamp = Instant.now(), memberId = selectedMemberId))
                    editingId = null
                    savedState.remove<String>(KEY_EDITING_ID)
                }
                arg == "draft" -> {
                    val taken = CaptureSessionHolder.take()
                    setDraft(
                        (taken ?: BpReadingDraft(timestamp = Instant.now()))
                            .let { if (it.memberId.isBlank()) it.copy(memberId = selectedMemberId) else it },
                    )
                    editingId = null
                    savedState.remove<String>(KEY_EDITING_ID)
                }
                else -> {
                    val id = runCatching { UUID.fromString(arg) }.getOrNull()
                    if (id != null) {
                        repo.findById(id)?.let {
                            setDraft(BpReadingDraft.fromReading(it))
                            editingId = it.id
                            savedState[KEY_EDITING_ID] = it.id.toString()
                        }
                    }
                }
            }
        }
    }

    fun update(transform: (BpReadingDraft) -> BpReadingDraft) {
        setDraft(transform(_draft.value))
    }

    fun clearCrisisWarning() { _crisisWarning.value = false }

    /** Single sink for draft mutations that also mirrors to the handle (M5). */
    private fun setDraft(d: BpReadingDraft) {
        _draft.value = d
        mirrorToHandle(d)
    }

    fun save(onDone: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        _saveError.value = null
        _crisisWarning.value = false
        viewModelScope.launch {
            val current = _draft.value
            Log.i(
                TAG,
                "[Confirm] save sys=${current.systolic} dia=${current.diastolic} " +
                    "pulse=${current.pulse ?: -1} src=${current.source} conf=${current.confidence}",
            )
            try {
                val photoFilename = current.photo?.let { writePhotoToDisk(it) } ?: current.photoFilename
                val reading = if (editingId != null) {
                    BpReading(
                        id = editingId!!,
                        systolic = current.systolic, diastolic = current.diastolic, pulse = current.pulse,
                        timestamp = current.timestamp, arm = current.arm, posture = current.posture,
                        partOfDay = current.partOfDay, beforeMedication = current.beforeMedication,
                        photoFilename = photoFilename, confidence = current.confidence,
                        source = current.source, note = current.note,
                        irregularHeartbeat = current.irregularHeartbeat,
                        // Carry the (possibly reassigned) attribution so editing
                        // doesn't silently re-home the reading to the owner.
                        memberId = current.memberId,
                    )
                } else {
                    current.toReading(photoFilename)
                }
                repo.upsert(reading)
                _saving.value = false
                if (reading.systolic >= 180 || reading.diastolic >= 120) {
                    _crisisWarning.value = true
                } else {
                    onDone()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[Confirm] save failed: ${e.message}", e)
                // 保留草稿並顯示錯誤,讓使用者可以重試
                _saving.value = false
                _saveError.value = context.getString(R.string.confirm_save_failed, e.message ?: context.getString(R.string.err_unknown))
            }
        }
    }

    /** Delete the reading being edited (no-op for a new/unsaved draft). */
    fun delete(onDone: () -> Unit) {
        val id = editingId ?: return onDone()
        viewModelScope.launch {
            runCatching { repo.delete(id) }
                .onFailure { Log.e(TAG, "[Confirm] delete failed: ${it.message}", it) }
            onDone()
        }
    }

    private suspend fun writePhotoToDisk(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val name = "${UUID.randomUUID()}.jpg"
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name
    }

    // ---- SavedStateHandle mirror (M5) ----
    // The transient photo Bitmap can't survive process death; only the on-disk
    // photo *filename* is mirrored (a fresh camera draft loses just its preview).

    private fun mirrorToHandle(d: BpReadingDraft) {
        savedState[KEY_SYS] = d.systolic
        savedState[KEY_DIA] = d.diastolic
        savedState[KEY_PULSE] = d.pulse ?: -1
        savedState[KEY_TIMESTAMP] = d.timestamp.toEpochMilli()
        savedState[KEY_ARM] = d.arm.name
        savedState[KEY_POSTURE] = d.posture.name
        savedState[KEY_PART] = d.partOfDay.name
        savedState[KEY_BEFORE_MED] = d.beforeMedication
        savedState[KEY_IRREGULAR] = d.irregularHeartbeat
        savedState[KEY_CONFIDENCE] = d.confidence
        savedState[KEY_NOTE] = d.note
        savedState[KEY_PHOTO] = d.photoFilename
        savedState[KEY_SOURCE] = d.source.name
        savedState[KEY_MEMBER_ID] = d.memberId
    }

    private fun restoreFromHandle(): BpReadingDraft = BpReadingDraft(
        systolic = savedState.get<Int>(KEY_SYS) ?: 0,
        diastolic = savedState.get<Int>(KEY_DIA) ?: 0,
        pulse = (savedState.get<Int>(KEY_PULSE) ?: -1).takeIf { it >= 0 },
        timestamp = Instant.ofEpochMilli(savedState.get<Long>(KEY_TIMESTAMP) ?: System.currentTimeMillis()),
        arm = runCatching { Arm.valueOf(savedState.get<String>(KEY_ARM) ?: "") }.getOrDefault(Arm.Left),
        posture = runCatching { Posture.valueOf(savedState.get<String>(KEY_POSTURE) ?: "") }.getOrDefault(Posture.Sitting),
        partOfDay = runCatching { PartOfDay.valueOf(savedState.get<String>(KEY_PART) ?: "") }.getOrDefault(PartOfDay.Morning),
        beforeMedication = savedState.get<Boolean>(KEY_BEFORE_MED) ?: true,
        irregularHeartbeat = savedState.get<Boolean>(KEY_IRREGULAR) ?: false,
        confidence = savedState.get<Double>(KEY_CONFIDENCE) ?: 1.0,
        note = savedState.get<String>(KEY_NOTE) ?: "",
        photoFilename = savedState.get<String>(KEY_PHOTO),
        source = runCatching { Source.valueOf(savedState.get<String>(KEY_SOURCE) ?: "") }.getOrDefault(Source.Manual),
        memberId = savedState.get<String>(KEY_MEMBER_ID) ?: "",
    )

    private companion object {
        const val KEY_SYS = "bp_sys"
        const val KEY_DIA = "bp_dia"
        const val KEY_PULSE = "bp_pulse"
        const val KEY_TIMESTAMP = "bp_timestamp"
        const val KEY_ARM = "bp_arm"
        const val KEY_POSTURE = "bp_posture"
        const val KEY_PART = "bp_part"
        const val KEY_BEFORE_MED = "bp_before_med"
        const val KEY_IRREGULAR = "bp_irregular"
        const val KEY_CONFIDENCE = "bp_confidence"
        const val KEY_NOTE = "bp_note"
        const val KEY_PHOTO = "bp_photo"
        const val KEY_SOURCE = "bp_source"
        const val KEY_MEMBER_ID = "bp_member_id"
        const val KEY_EDITING_ID = "bp_editing_id"
    }
}
