package com.silverbp.android.ui.coach

import com.silverbp.android.coach.DayOfWeekMask
import com.silverbp.android.core.db.MedicationScheduleEntity
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** "Mon, Wed, Fri" — short locale-aware day names. */
internal fun formatDaysOfWeek(mask: Int, locale: Locale = Locale.getDefault()): String {
    if (DayOfWeekMask.isEmpty(mask)) return ""
    if ((mask and DayOfWeekMask.ALL) == DayOfWeekMask.ALL) {
        return DayOfWeek.values().joinToString(", ") {
            it.getDisplayName(TextStyle.SHORT, locale)
        }
    }
    return DayOfWeek.values()
        .filter { DayOfWeekMask.contains(mask, it) }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
}

/** "08:00" */
internal fun formatTime(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour, minute)

/**
 * Compact "Mon, Wed, Fri • 08:00, 20:00" style summary across a medication's
 * schedule rows. Groups rows that share the same daysOfWeekMask to keep the
 * line readable (instead of repeating "Mon, Wed, Fri" twice).
 */
internal fun formatScheduleSummary(
    schedules: List<MedicationScheduleEntity>,
    locale: Locale = Locale.getDefault(),
): String {
    val enabled = schedules.filter { it.enabled }
    if (enabled.isEmpty()) return ""
    return enabled
        .groupBy { it.daysOfWeekMask }
        .entries
        .sortedBy { it.key }
        .joinToString("\n") { (mask, rows) ->
            val days = formatDaysOfWeek(mask, locale)
            val times = rows.sortedWith(compareBy({ it.hour }, { it.minute }))
                .joinToString(", ") { formatTime(it.hour, it.minute) }
            "$days • $times"
        }
}
