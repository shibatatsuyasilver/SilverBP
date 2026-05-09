package com.silverbp.android.coach

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.silverbp.android.core.db.MedicationDoseEntity
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the "Mark as taken" notification action. Receives an explicit
 * intent from [CoachNotifier.postMedicationReminder], upserts a
 * [MedicationDoseEntity] with `taken = true`, then dismisses the notification.
 *
 * Uses [BroadcastReceiver.goAsync] for the < 10s DB write — the canonical
 * pattern for short notification-action work that doesn't warrant a Service
 * or Worker.
 *
 * The dose row id is derived from `(dayStart, scheduleId)` so:
 *   - repeated taps of the same notification upsert one row
 *   - the in-app log screen's Switch toggle for the same (med, schedule)
 *     refers to the same row, so the two paths stay in sync
 */
class MedicationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_TAKEN) return
        val medicationId = intent.getStringExtra(EXTRA_MEDICATION_ID) ?: return
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val dayStart = intent.getLongExtra(EXTRA_DAY_START, -1L)
        val scheduledHour = intent.getIntExtra(EXTRA_SCHEDULED_HOUR, -1)
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (dayStart < 0 || scheduledHour < 0) return

        val pending = goAsync()
        scope.launch {
            try {
                ServiceLocator.coachRepository.upsertDose(
                    MedicationDoseEntity(
                        id = doseId(dayStart, scheduleId),
                        dayStart = dayStart,
                        medicationId = medicationId,
                        scheduledHour = scheduledHour,
                        taken = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                if (notifId >= 0) {
                    NotificationManagerCompat.from(context).cancel(notifId)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[MedAction] mark-taken failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MedActionReceiver"
        const val ACTION_MARK_TAKEN = "com.silverbp.android.coach.action.MARK_TAKEN"
        const val EXTRA_MEDICATION_ID = "medicationId"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        const val EXTRA_DAY_START = "dayStart"
        const val EXTRA_SCHEDULED_HOUR = "scheduledHour"
        const val EXTRA_NOTIF_ID = "notifId"

        /** Deterministic dose id; shared by the in-app Switch and the notification action. */
        fun doseId(dayStart: Long, scheduleId: String): String =
            "med-$dayStart-$scheduleId"

        // Receiver is process-scoped; goAsync() blocks process death until pending.finish().
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
