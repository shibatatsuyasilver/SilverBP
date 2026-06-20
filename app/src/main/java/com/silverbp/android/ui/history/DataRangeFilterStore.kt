package com.silverbp.android.ui.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped, in-memory holder for the date-range filter SHARED between the
 * 紀錄 (UnifiedHistory) and 分析 (Insights / Glucose / Weight) segments of the Data
 * hub, so a range picked in one segment applies to the other ("連動共用"). Held as a
 * [StateFlow] so a newly-subscribing segment immediately replays the latest value
 * (the 分析 chips reflect a range picked in 紀錄 the instant 分析 first composes — no
 * flicker), unlike [com.silverbp.android.core.member.CurrentMemberStore]'s bare Flow.
 *
 * Deliberately NOT persisted — matches the previously non-persisted 紀錄 filter; the
 * sharing is within-session only. The standalone per-type history view-models
 * ([HistoryViewModel] / [GlucoseHistoryViewModel] / [WeightHistoryViewModel]) keep
 * their own private range and are intentionally unaffected.
 */
class DataRangeFilterStore {
    private val _range = MutableStateFlow(DateRange.All)
    val range: StateFlow<DateRange> = _range.asStateFlow()
    fun set(r: DateRange) { _range.value = r }
}
