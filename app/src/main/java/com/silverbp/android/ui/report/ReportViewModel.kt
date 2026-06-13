package com.silverbp.android.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.R
import com.silverbp.android.billing.EntitlementManager
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class ReportRange(val days: Long?) {
    ThisMonth(null), LastMonth(null), Last30(30), Last90(90), AllTime(null);
}

data class ReportUiState(
    val range: ReportRange = ReportRange.Last30,
    val readings: List<BpReading> = emptyList(),
    val generatedFile: File? = null,
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
    // Premium tiering (Phase 3): false → the free summary-only PDF was produced
    // and the screen should show the "full report is Premium" upsell note. With
    // PREMIUM_ENFORCED=false isPremium() is always true, so this stays true and
    // the upsell never shows (zero behaviour change vs. today).
    val isPremium: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val members: MemberRepository = ServiceLocator.memberRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val entitlements: EntitlementManager = ServiceLocator.entitlementManager,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(ReportRange.Last30)
    private val generatedFlow = MutableStateFlow<File?>(null)
    private val generatingFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<ReportUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        rangeFlow,
        generatedFlow,
        generatingFlow,
        errorFlow,
        // entitlement is folded in only as a recomposition trigger so the upsell
        // note re-evaluates when the resolved tier changes; the actual decision is
        // entitlements.isPremium() (layers BuildConfig + debug override).
        entitlements.entitlement,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<BpReading>
        val range = values[1] as ReportRange
        val file = values[2] as File?
        val generating = values[3] as Boolean
        val error = values[4] as String?
        val (from, to) = boundsFor(range)
        val filtered = all.filter { it.timestamp in from..to }
        ReportUiState(range, filtered, file, generating, error, isPremium = entitlements.isPremium())
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
                val memberId = currentMember.current()
                val all = repo.observeAll(memberId).first()
                val readings = all.filter { it.timestamp in from..to }
                // Cover prints whose readings these are — doctors need the name in
                // a multi-member family. But a solo install (chip hidden, never
                // opted into family) must NOT get a redundant "Subject: Me" line on
                // the doctor-facing PDF (roadmap §3-7 "single-user installs are
                // unaffected"). So only resolve a subject when there's more than
                // one active member, mirroring the switcher-chip / attribution-row
                // visibility rule; with a single member pass blank so the renderer's
                // isNotBlank() guard omits the line. The "Me" fallback covers an
                // unnamed non-owner only in the multi-member case (never persisted
                // as a literal — audit M27).
                val multiMember = members.observeActive().first().size > 1
                val name = if (!multiMember) {
                    ""
                } else {
                    members.findById(UUID.fromString(memberId))?.displayName
                        ?.takeIf { it.isNotBlank() }
                        ?: ServiceLocator.context.getString(R.string.member_me)
                }
                // Premium tiering (Phase 3): free tier gets cover summary +
                // disclaimer only; Premium gets the full per-reading table. The
                // free summary is NEVER blocked. With PREMIUM_ENFORCED=false this
                // is always true → full report, exactly as before.
                val file = com.silverbp.android.reporting.PdfReportRenderer(ServiceLocator.context)
                    .render(
                        readings,
                        from = from,
                        to = to,
                        memberName = name,
                        includeDetail = entitlements.isPremium(),
                    )
                generatedFlow.value = file
                onReady(file)
            } catch (t: Throwable) {
                // Disk full / no write permission / renderer failure: never let
                // the tap silently no-op — surface a plain message.
                android.util.Log.w("ReportViewModel", "PDF generation failed", t)
                errorFlow.value = ServiceLocator.context.getString(R.string.report_generate_failed)
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
