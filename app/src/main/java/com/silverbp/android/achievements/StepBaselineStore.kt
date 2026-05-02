package com.silverbp.android.achievements

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.achievementsDataStore by preferencesDataStore(name = "achievements_internal")

/**
 * Thin DataStore wrapper for the rolling sensor-baseline used to compute
 * "today's steps" when Health Connect isn't available. Internal state — not
 * exposed in [com.silverbp.android.settings.UserSettings] because the user
 * doesn't tune it.
 *
 * `baselineRaw` is the cumulative TYPE_STEP_COUNTER value snapshotted the
 * first time we observed the sensor on the day stored in `baselineDayStart`
 * (start-of-day epoch millis).
 */
class StepBaselineStore(private val context: Context) {

    private object Keys {
        val BASELINE_RAW = longPreferencesKey("sensor_baseline_raw")
        val BASELINE_DAY = longPreferencesKey("sensor_baseline_day_start")
    }

    suspend fun read(): Pair<Long?, Long?> {
        val prefs = context.achievementsDataStore.data.first()
        return prefs[Keys.BASELINE_RAW] to prefs[Keys.BASELINE_DAY]
    }

    suspend fun write(raw: Long, dayStart: Long) {
        context.achievementsDataStore.edit {
            it[Keys.BASELINE_RAW] = raw
            it[Keys.BASELINE_DAY] = dayStart
        }
    }
}
