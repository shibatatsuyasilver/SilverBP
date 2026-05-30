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
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
)

class ReportViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(ReportRange.Last30)
    private val generatedFlow = MutableStateFlow<File?>(null)
    private val generatingFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<ReportUiState> = combine(
        repo.observeAll(),
        rangeFlow,
        generatedFlow,
        generatingFlow,
        errorFlow,
    ) { all, range, file, generating, error ->
        val (from, to) = boundsFor(range)
        val filtered = all.filter { it.timestamp in from..to }
        ReportUiState(range, filtered, file, generating, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportUiState())

    fun setRange(r: ReportRange) {
        rangeFlow.value = r
        generatedFlow.value = null
        errorFlow.value = null
    }

    fun generate(onReady: (File) -> Unit) {
        if (generatingFlow.value) return
        viewModelScope.launch {
            generatingFlow.value = true
            errorFlow.value = null
            try {
                val (from, to) = boundsFor(rangeFlow.value)
                val all = repo.observeAll().first()
                val readings = all.filter { it.timestamp in from..to }
                val file = com.silverbp.android.reporting.PdfReportRenderer(ServiceLocator.context)
                    .render(readings, from = from, to = to)
                generatedFlow.value = file
                onReady(file)
            } catch (t: Throwable) {
                // Disk full / no write permission / renderer failure: never let
                // the tap silently no-op — surface a plain message.
                android.util.Log.w("ReportViewModel", "PDF generation failed", t)
                errorFlow.value = "報告產生失敗,請確認儲存空間後再試"
            } finally {
                generatingFlow.value = false
            }
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
