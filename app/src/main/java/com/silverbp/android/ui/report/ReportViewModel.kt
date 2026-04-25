package com.silverbp.android.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class ReportRange(val days: Long?) {
    ThisMonth(null), LastMonth(null), Last30(30), Last90(90), AllTime(null);
}

data class ReportUiState(
    val range: ReportRange = ReportRange.Last30,
    val readings: List<BpReading> = emptyList(),
    val generatedFile: File? = null,
)

class ReportViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(ReportRange.Last30)
    private val generatedFlow = MutableStateFlow<File?>(null)

    val state: StateFlow<ReportUiState> = combine(
        repo.observeAll(),
        rangeFlow,
        generatedFlow,
    ) { all, range, file ->
        val (from, to) = boundsFor(range)
        val filtered = all.filter { it.timestamp in from..to }
        ReportUiState(range, filtered, file)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportUiState())

    fun setRange(r: ReportRange) {
        rangeFlow.value = r
        generatedFlow.value = null
    }

    fun generate(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val (from, to) = boundsFor(rangeFlow.value)
            val all = repo.observeAll().first()
            val readings = all.filter { it.timestamp in from..to }
            val file = com.silverbp.android.reporting.PdfReportRenderer(ServiceLocator.context)
                .render(readings, from = from, to = to)
            generatedFlow.value = file
            onReady(file)
        }
    }

    private fun boundsFor(range: ReportRange): Pair<Instant, Instant> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = Instant.now()
        return when (range) {
            ReportRange.ThisMonth -> {
                val startOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant()
                startOfMonth to now
            }
            ReportRange.LastMonth -> {
                val firstOfThis = today.withDayOfMonth(1)
                val firstOfLast = firstOfThis.minusMonths(1)
                firstOfLast.atStartOfDay(zone).toInstant() to firstOfThis.atStartOfDay(zone).toInstant()
            }
            ReportRange.Last30 -> Instant.now().minus(30, ChronoUnit.DAYS) to now
            ReportRange.Last90 -> Instant.now().minus(90, ChronoUnit.DAYS) to now
            ReportRange.AllTime -> Instant.EPOCH to now
        }
    }
}
