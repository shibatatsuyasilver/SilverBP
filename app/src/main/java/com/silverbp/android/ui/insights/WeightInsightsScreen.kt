package com.silverbp.android.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.WeightGuideline
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightRepository
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.smoothLinePath
import com.silverbp.android.ui.components.weightCategoryLabel
import com.silverbp.android.ui.components.weightColorFor
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Member-scoped weight insights, the glucose [GlucoseInsightsScreen] sibling. Range
 * chips (7/30/90/All) drive a weight trend chart (canonical kg over time, rendered
 * in the user's [WeightUnit], with the member's target weight as a dashed guide
 * line) plus a summary card: latest weight, the change across the range, and the
 * latest BMI + Asia-Pacific category (coloured via [weightColorFor]). Single-member
 * only this phase (compare mode is out of scope — see [WeightInsightsViewModel]).
 */
@Composable
fun WeightInsightsScreen(
    modifier: Modifier = Modifier,
    vm: WeightInsightsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.height(AppSpacing.itemGap))
        WeightRangeChips(state.range, vm::setRange)
        WeightSummaryCards(state)

        ChartCard(stringResource(R.string.weight_trend_title)) {
            WeightTrendChart(
                readings = state.readings,
                unit = state.unit,
                targetKg = state.targetWeightKg,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WeightRangeChips(current: InsightsRange, onSelect: (InsightsRange) -> Unit) {
    val pairs = listOf(
        InsightsRange.Last7 to stringResource(R.string.range_7d),
        InsightsRange.Last30 to stringResource(R.string.range_30d),
        InsightsRange.Last90 to stringResource(R.string.range_90d),
        InsightsRange.All to stringResource(R.string.range_all),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        pairs.forEach { (r, label) ->
            FilterChip(
                selected = current == r,
                onClick = { onSelect(r) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/**
 * Three headline stats: latest weight, the across-range change (signed), and the
 * latest BMI + category. BMI is null until both a reading and the member's height
 * exist, so its chip falls back to the em-dash like the glucose stats.
 */
@Composable
private fun WeightSummaryCards(state: WeightInsightsUiState) {
    val unitLabel = weightUnitLabel(state.unit)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        StatChip(
            label = stringResource(R.string.weight_summary_latest),
            value = state.latestKg?.let { "${formatWeightValue(it, state.unit)} $unitLabel" } ?: "—",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatChip(
            label = stringResource(R.string.weight_summary_change),
            value = state.changeKg?.let { formatWeightChange(it, state.unit, unitLabel) } ?: "—",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        val category = state.latestBmi?.let { WeightGuideline.classify(it) }
        StatChip(
            label = stringResource(R.string.weight_bmi_label),
            value = state.latestBmi?.let { "%.1f".format(it) } ?: "—",
            valueColor = category?.let { weightColorFor(it) },
            sub = category?.let { weightCategoryLabel(it) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    sub: String? = null,
) {
    StandardCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    StandardCard(
        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        title = title,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        content()
    }
}

/**
 * Weight trend: a smooth value-over-time line, values plotted in the user's
 * [unit] so the y-axis ticks read naturally. The member's target weight (if set)
 * is drawn as a dashed horizontal guide line. Follows the Canvas idiom of the
 * glucose trend chart (gridlines, [smoothLinePath]) but weight-specific so it
 * leaves the shared charts untouched.
 */
@Composable
private fun WeightTrendChart(
    readings: List<WeightReading>,
    unit: WeightUnit,
    targetKg: Double?,
    modifier: Modifier = Modifier,
) {
    if (readings.size < 2) {
        EmptyWeightChart(stringResource(R.string.weight_no_data), modifier.height(220.dp))
        return
    }
    // Plot values in the user's preferred unit so the y-axis ticks read naturally.
    val values = readings.map { it.valueIn(unit) }
    val target = targetKg?.let { kgToUnit(it, unit) }
    val padding = if (unit == WeightUnit.Kg) 1.0 else 2.0
    // Fold the target into the y-extent so the guide line is always on-canvas.
    val lo = (listOfNotNull(values.min(), target).min() - padding).coerceAtLeast(0.0)
    val hi = listOfNotNull(values.max(), target).max() + padding
    val yMin = lo
    val yMax = hi

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.tertiary
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(color = gridColor, fontSize = 10.sp)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (target != null) {
            LegendDot(targetColor, stringResource(R.string.weight_target_label))
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(8.dp),
        ) {
            val plotR = (size.width - 36f).coerceAtLeast(1f)
            val plotT = 8f
            val plotB = (size.height - 8f).coerceAtLeast(plotT + 1f)
            fun xOf(i: Int) = if (values.size <= 1) 0f else i.toFloat() / (values.size - 1) * plotR
            fun yOf(v: Double): Float {
                if (yMax <= yMin) return (plotT + plotB) / 2f
                val t = (v - yMin) / (yMax - yMin)
                return (plotB - t * (plotB - plotT)).toFloat()
            }

            // y-axis gridlines + ticks (4 bands)
            for (g in 0..3) {
                val v = yMin + (yMax - yMin) * (3 - g) / 3.0
                val gy = yOf(v)
                drawLine(gridColor.copy(alpha = 0.15f), Offset(0f, gy), Offset(plotR, gy), strokeWidth = 1f)
                val lay = measurer.measure("%.1f".format(v), tickStyle)
                drawText(lay, topLeft = Offset(plotR + 4f, gy - lay.size.height / 2f))
            }

            // Target guide line (dashed), drawn under the trend so the line reads on top.
            if (target != null) {
                val ty = yOf(target)
                drawLine(
                    targetColor,
                    Offset(0f, ty),
                    Offset(plotR, ty),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                )
            }

            val pts = values.mapIndexed { i, v -> Offset(xOf(i), yOf(v)) }
            drawPath(
                smoothLinePath(pts),
                color = lineColor,
                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            pts.forEach { p ->
                drawCircle(lineColor.copy(alpha = 0.95f), radius = 6f, center = p)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyWeightChart(label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================
// Display helpers (mirror the Today card / history formatting).
// ============================================================

/** Localized unit label (公斤 / 磅) for a [WeightUnit]. */
@Composable
private fun weightUnitLabel(unit: WeightUnit): String = stringResource(
    when (unit) {
        WeightUnit.Kg -> R.string.weight_unit_kg
        WeightUnit.Lb -> R.string.weight_unit_lb
    },
)

/** Canonical kg → a value in [unit] (kg passes through; lb converts). */
private fun kgToUnit(kg: Double, unit: WeightUnit): Double = when (unit) {
    WeightUnit.Kg -> kg
    WeightUnit.Lb -> WeightUnit.kgToLb(kg)
}

/** A canonical-kg value formatted to one decimal in [unit] (both read naturally). */
private fun formatWeightValue(valueKg: Double, unit: WeightUnit): String =
    "%.1f".format(kgToUnit(valueKg, unit))

/** Signed across-range change, formatted in [unit] with an explicit + for gains. */
private fun formatWeightChange(changeKg: Double, unit: WeightUnit, unitLabel: String): String {
    val v = kgToUnit(changeKg, unit)
    val sign = if (v > 0) "+" else if (v < 0) "−" else ""
    return "$sign${"%.1f".format(kotlin.math.abs(v))} $unitLabel"
}

// ============================================================
// ViewModel + pure analytics
// ============================================================

/**
 * Weight analytics state for [WeightInsightsScreen]. Single-member only this phase.
 * Reuses the BP [InsightsRange] enum for the 7/30/90/All chips. [readings] is
 * range-filtered + time-sorted for the trend chart; all weights are canonical kg
 * and the screen renders them in [unit]. [latestBmi] / [targetWeightKg] derive
 * from the member's profile (height / target), so both are null until set.
 */
data class WeightInsightsUiState(
    val range: InsightsRange = InsightsRange.Last30,
    val readings: List<WeightReading> = emptyList(),
    /** Latest in-range weight (kg), or null when the range is empty. */
    val latestKg: Double? = null,
    /** Latest minus earliest in-range weight (kg); null with fewer than two readings. */
    val changeKg: Double? = null,
    /** BMI of the latest in-range reading; null when height is missing/invalid. */
    val latestBmi: Double? = null,
    /** Member's target weight (kg) for the chart guide line; null when unset. */
    val targetWeightKg: Double? = null,
    val unit: WeightUnit = WeightUnit.Kg,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

/**
 * Member-scoped weight insights, the glucose [GlucoseInsightsViewModel] sibling.
 * Follows the selected member ([CurrentMemberStore]) and the chosen [InsightsRange].
 *
 * **Compare mode is out of scope this phase** (per the Phase 5 contract): weight's
 * BMI/category is a per-member concern keyed on the member's height, and a faithful
 * multi-member overlay would have to reconcile per-member heights/targets, so
 * single-member is the shipped behaviour; the member switcher still scopes the view.
 *
 * The member's height (for BMI) and target weight (for the chart guide) are re-read
 * off the member row whenever the selection changes (mirrors [TodayViewModel]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeightInsightsViewModel(
    private val repo: WeightRepository = ServiceLocator.weightRepository,
    private val members: MemberRepository = ServiceLocator.memberRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(InsightsRange.Last30)
    private val unitFlow = settings.flow.map { WeightUnit.fromRaw(it.weightUnit) }

    // Readings paired with the member's profile (height for BMI, target for the
    // guide line), re-resolved per member selection like TodayViewModel.
    private val memberWeightFlow = currentMember.flow.flatMapLatest { id ->
        repo.observeAll(id).map { all ->
            val member = runCatching { members.findById(UUID.fromString(id)) }.getOrNull()
            Triple(all, member?.heightCm, member?.targetWeightKg)
        }
    }

    val state: StateFlow<WeightInsightsUiState> = combine(
        memberWeightFlow,
        rangeFlow,
        unitFlow,
    ) { (all, heightCm, targetKg), range, unit ->
        val summary = computeWeightInsights(all, range, heightCm)
        WeightInsightsUiState(
            range = range,
            readings = summary.readings,
            latestKg = summary.latestKg,
            changeKg = summary.changeKg,
            latestBmi = summary.latestBmi,
            targetWeightKg = targetKg,
            unit = unit,
            isLoading = false,
        )
    }
        .catch { emit(WeightInsightsUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightInsightsUiState())

    fun setRange(r: InsightsRange) { rangeFlow.value = r }
}

/** Pure weight analytics, mirroring [computeGlucoseInsights]. No Android deps. */
data class WeightSummary(
    val readings: List<WeightReading> = emptyList(),
    val latestKg: Double? = null,
    val changeKg: Double? = null,
    val latestBmi: Double? = null,
)

fun computeWeightInsights(
    all: List<WeightReading>,
    range: InsightsRange,
    heightCm: Int?,
): WeightSummary {
    val cutoff = range.days?.let { Instant.now().minus(it, ChronoUnit.DAYS) }
    val filtered = (if (cutoff == null) all else all.filter { it.timestamp.isAfter(cutoff) })
        .sortedBy { it.timestamp }
    val first = filtered.firstOrNull()?.valueKg
    val latest = filtered.lastOrNull()?.valueKg
    return WeightSummary(
        readings = filtered,
        latestKg = latest,
        changeKg = if (filtered.size >= 2 && first != null && latest != null) latest - first else null,
        latestBmi = latest?.let { WeightGuideline.bmi(it, heightCm) },
    )
}
