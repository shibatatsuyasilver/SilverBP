package com.silverbp.android.coach

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first

/**
 * Fires once per scheduled (day-of-week × time) instant. After posting the
 * notification, re-enqueues itself for the next matching instant via
 * [MedicationReminderScheduler.scheduleOne] — this is the canonical "alarm
 * style" pattern with WorkManager.
 *
 * Resilience: a stale schedule (deleted while the worker was queued) returns
 * `success` without notifying. A user-disabled-Coach run also no-ops; the
 * cold-start reconciler in `SilverBpApplication` cancels everything when the
 * toggle is off, so this is just a safety net.
 */
class MedicationReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(MedicationReminderScheduler.KEY_SCHEDULE_ID)
            ?: return Result.success()
        val settings = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        if (settings?.enableCoach != true) return Result.success()

        return runCatching {
            val schedule = ServiceLocator.database.medicationScheduleDao().findById(scheduleId)
                ?: return@runCatching Result.success()
            if (!schedule.enabled) return@runCatching Result.success()
            val med = ServiceLocator.database.medicationDao().findById(schedule.medicationId)
                ?: return@runCatching Result.success()

            // Resolve the owning member so a non-owner family member's medication
            // is addressed by name (v18). A missing member row falls back to the
            // owner's unchanged copy; a blank displayName uses the "Me" string.
            val memberRow = ServiceLocator.database.medicationDoseDao()
                .memberForMedication(med.id)
            val ownerMedication = memberRow?.isOwner ?: true
            val memberName = memberRow?.displayName
                ?.ifBlank { applicationContext.getString(R.string.member_me) }
                .orEmpty()

            CoachNotifier.postMedicationReminder(
                applicationContext,
                med,
                schedule,
                ownerMedication = ownerMedication,
                memberName = memberName,
            )

            // Self-reschedule for the next matching instant. If this throws,
            // the cold-start sweep in SilverBpApplication.reconcileCoach will
            // re-enqueue on the next launch.
            MedicationReminderScheduler.scheduleOne(applicationContext, scheduleId)
            Result.success()
        }.getOrElse { t ->
            Log.w(TAG, "[MedReminder] worker failed; will retry", t)
            Result.retry()
        }
    }

    private companion object { const val TAG = "MedReminderWorker" }
}
