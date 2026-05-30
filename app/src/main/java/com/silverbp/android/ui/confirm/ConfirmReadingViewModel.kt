package com.silverbp.android.ui.confirm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.capture.CaptureSessionHolder
import com.silverbp.android.core.Arm
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.core.Posture
import com.silverbp.android.core.Source
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

private const val TAG = "ConfirmReading"

class ConfirmReadingViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val context: Context = ServiceLocator.context,
) : ViewModel() {

    private val _draft = MutableStateFlow(BpReadingDraft())
    val draft: StateFlow<BpReadingDraft> = _draft.asStateFlow()

    private var editingId: UUID? = null

    /** True when this screen is editing an existing reading (vs. confirming a new one). */
    val isEditing: Boolean get() = editingId != null

    /**
     * Initialise from a navigation argument:
     *  - "new"   → blank manual draft
     *  - "draft" → consume current capture-session draft (with photo + AI confidence)
     *  - <uuid>  → load existing reading for edit
     */
    fun initWith(arg: String?) {
        viewModelScope.launch {
            when {
                arg == null || arg == "new" -> {
                    _draft.value = BpReadingDraft(timestamp = Instant.now())
                    editingId = null
                }
                arg == "draft" -> {
                    val taken = CaptureSessionHolder.take()
                    _draft.value = taken ?: BpReadingDraft(timestamp = Instant.now())
                    editingId = null
                }
                else -> {
                    val id = runCatching { UUID.fromString(arg) }.getOrNull()
                    if (id != null) {
                        repo.findById(id)?.let {
                            _draft.value = BpReadingDraft.fromReading(it)
                            editingId = it.id
                        }
                    }
                }
            }
        }
    }

    fun update(transform: (BpReadingDraft) -> BpReadingDraft) {
        _draft.value = transform(_draft.value)
    }

    fun save(onDone: () -> Unit) {
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
                    )
                } else {
                    current.toReading(photoFilename)
                }
                repo.upsert(reading)
                onDone()
            } catch (e: Throwable) {
                Log.e(TAG, "[Confirm] save failed: ${e.message}", e)
                throw e
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
}
