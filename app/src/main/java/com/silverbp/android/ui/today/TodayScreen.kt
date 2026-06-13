package com.silverbp.android.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.ui.member.MemberSwitcherChip
import com.silverbp.android.ui.components.BpReadingValue
import com.silverbp.android.ui.components.ModelLoadBanner
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.categoryLabel
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
import com.silverbp.android.ui.components.formatGlucoseValue
import com.silverbp.android.ui.components.glucoseCategoryLabel
import com.silverbp.android.ui.components.glucoseColorFor
import com.silverbp.android.ui.components.glucoseUnitLabel
import com.silverbp.android.ui.components.measureContextLabel
import com.silverbp.android.ui.theme.AppSpacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today tab. Owner decision §1/§2: a single unified daily-record card titled with
 * today's date holds an equal-footing **blood-pressure** section and a **blood-
 * glucose** section, each showing today's latest reading (or an inline "今天還沒
 * 記錄 · 記一筆" prompt). Replaces the old latest-ever BP hero + separate glucose
 * card. Today-scoped via [TodayViewModel] (system-tz calendar day), member-scoped
 * exactly like the old cards.
 *
 * The top-right "+" opens the [AddMeasurementSheet] chooser (量血壓 / 量血糖):
 * [onCaptureBp] opens BP capture, [onCaptureGlucose] opens the meter-capture flow.
 * The unified card's per-section inline "記一筆" prompts reuse those same two
 * callbacks. Tapping a shown reading edits it via [onEditBp] / [onEditGlucose]
 * (the existing Confirm edit routes; id as a string). The "今天 N 筆" affordance
 * (a section with >1 reading today) opens that type's history via
 * [onViewBpHistory] / [onViewGlucoseHistory].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    // A day's record now covers both BP and glucose, so the top-right "+" is a
    // chooser (量血壓 / 量血糖). onCaptureBp opens the BP camera; onCaptureGlucose
    // opens the meter-capture flow. The unified card's per-section inline
    // "記一筆" prompts reuse these same two callbacks.
    onCaptureBp: () -> Unit,
    onCaptureGlucose: () -> Unit,
    onAddManual: () -> Unit,
    onOpenSettings: () -> Unit,
    // Default no-op so AppNavHost compiles unchanged until it wires the
    // MEMBER_MANAGE navigation (the chip self-hides for single-member installs).
    onManageMembers: () -> Unit = {},
    // Tapping a Today-card reading edits it via the existing Confirm flows.
    // Defaults are no-ops so previews / partial wiring still compile.
    onEditBp: (String) -> Unit = {},
    onEditGlucose: (String) -> Unit = {},
    // "今天 N 筆" affordance → that type's history. Default no-op until the nav
    // track wires the unified-history routes (cross-track callback).
    onViewBpHistory: () -> Unit = {},
    onViewGlucoseHistory: () -> Unit = {},
    vm: TodayViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // The "+" opens the record chooser (AddMeasurementSheet); this is the only
    // state the host owns for it — the sheet renders nothing while false.
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.today_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_a11y),
                        )
                    }
                },
                actions = {
                    MemberSwitcherChip(onManageMembers = onManageMembers)
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(
                            Icons.Filled.AddCircle,
                            contentDescription = stringResource(R.string.add_reading_a11y),
                        )
                    }
                },
            )
        },
    ) { padding ->
        AddMeasurementSheet(
            visible = showAddSheet,
            onDismiss = { showAddSheet = false },
            onCaptureBp = onCaptureBp,
            onCaptureGlucose = onCaptureGlucose,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            ModelLoadBanner(phase = state.modelPhase)
            Spacer(Modifier.height(AppSpacing.tight))

            Text(
                text = stringResource(greetingRes()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = AppSpacing.screenH),
            )

            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                state.error -> Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.error_load_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    // Inline "記一筆" prompts reuse the capture callbacks (the same
                    // entry points the "+" chooser offers).
                    TodayRecordCard(
                        state = state,
                        onRecordBp = onCaptureBp,
                        onRecordGlucose = onCaptureGlucose,
                        onEditBp = onEditBp,
                        onEditGlucose = onEditGlucose,
                        onViewBpHistory = onViewBpHistory,
                        onViewGlucoseHistory = onViewGlucoseHistory,
                        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                    )

                    // Pro-tip is keyed off today's latest BP reading's classification,
                    // placed below the unified card. Hidden when there's no BP today
                    // (nothing to classify) — the inline prompt covers that case.
                    state.todayBp.maxByOrNull { it.timestamp }?.let { latestBp ->
                        StandardCard(
                            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                            title = stringResource(R.string.today_protip_title),
                        ) {
                            Text(
                                text = stringResource(
                                    proTipRes(latestBp.systolic, latestBp.diastolic, state.guideline),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(AppSpacing.sectionGap))
                }
            }
        }
    }
}

/**
 * The unified daily-record card: title = today's date, then the BP section, a
 * divider, then the glucose section. Each section is either today's latest
 * reading (+ a "今天 N 筆" affordance when there's more than one) or an inline
 * "今天還沒記錄 · 記一筆" prompt.
 */
@Composable
private fun TodayRecordCard(
    state: TodayUiState,
    onRecordBp: () -> Unit,
    onRecordGlucose: () -> Unit,
    onEditBp: (String) -> Unit,
    onEditGlucose: (String) -> Unit,
    onViewBpHistory: () -> Unit,
    onViewGlucoseHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StandardCard(
        modifier = modifier,
        cornerRadius = AppSpacing.heroCorner,
        title = stringResource(R.string.today_card_title, formatToday(state.today)),
    ) {
        BpSection(
            readings = state.todayBp,
            guideline = state.guideline,
            onRecord = onRecordBp,
            onEdit = onEditBp,
            onViewHistory = onViewBpHistory,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        GlucoseSection(
            readings = state.todayGlucose,
            unit = state.glucoseUnit,
            onRecord = onRecordGlucose,
            onEdit = onEditGlucose,
            onViewHistory = onViewGlucoseHistory,
        )
    }
}

/** Blood-pressure section of the unified card (today's latest, or inline prompt). */
@Composable
private fun BpSection(
    readings: List<BpReading>,
    guideline: HypertensionGuideline,
    onRecord: () -> Unit,
    onEdit: (String) -> Unit,
    onViewHistory: () -> Unit,
) {
    val latest = readings.maxByOrNull { it.timestamp }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
        SectionHeader(
            title = stringResource(R.string.today_section_bp),
            count = readings.size,
            onViewHistory = onViewHistory,
        )

        if (latest == null) {
            EmptySectionPrompt(
                onRecord = onRecord,
                recordCd = stringResource(R.string.today_record_bp_a11y),
            )
        } else {
            val cat = classify(latest.systolic, latest.diastolic, guideline)
            val color = colorFor(cat)
            val editCd = stringResource(R.string.today_edit_bp_a11y)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onEdit(latest.id.toString()) }
                    .semantics { contentDescription = editCd },
                verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        categoryLabel(cat),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BpReadingValue(systolic = latest.systolic, diastolic = latest.diastolic)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.mmhg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    latest.pulse?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.MonitorHeart,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "$it ${stringResource(R.string.bpm)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        timeFmt().format(latest.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Blood-glucose section of the unified card (today's latest, or inline prompt). */
@Composable
private fun GlucoseSection(
    readings: List<GlucoseReading>,
    unit: GlucoseUnit,
    onRecord: () -> Unit,
    onEdit: (String) -> Unit,
    onViewHistory: () -> Unit,
) {
    val latest = readings.maxByOrNull { it.timestamp }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
        SectionHeader(
            title = stringResource(R.string.today_section_glucose),
            count = readings.size,
            onViewHistory = onViewHistory,
        )

        if (latest == null) {
            EmptySectionPrompt(
                onRecord = onRecord,
                recordCd = stringResource(R.string.today_record_glucose_a11y),
            )
        } else {
            val cat = remember(latest) {
                GlucoseClassifier().classify(latest.valueMgdl, latest.measureContext)
            }
            val color = glucoseColorFor(cat)
            val editCd = stringResource(R.string.today_edit_glucose_a11y)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onEdit(latest.id.toString()) }
                    .semantics { contentDescription = editCd },
                verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        glucoseCategoryLabel(cat),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        formatGlucoseValue(latest.valueMgdl, unit),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        glucoseUnitLabel(unit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    "${measureContextLabel(latest.measureContext)} · ${timeFmt().format(latest.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A section's heading row: the section label plus, when the section has more than
 * one reading today, a "今天 N 筆" text affordance that opens that type's history.
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    onViewHistory: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (count > 1) {
            TextButton(onClick = onViewHistory) {
                Text(stringResource(R.string.today_count_today, count))
            }
        }
    }
}

/** Inline "今天還沒記錄 · 記一筆" prompt for an empty section. */
@Composable
private fun EmptySectionPrompt(
    onRecord: () -> Unit,
    recordCd: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.today_empty_today),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRecord,
            modifier = Modifier.semantics { contentDescription = recordCd },
        ) {
            Text(stringResource(R.string.today_record_one))
        }
    }
}

/** Time-of-day greeting chosen from the local hour. */
private fun greetingRes(): Int = when (LocalTime.now().hour) {
    in 0 until 12 -> R.string.today_greeting_morning
    in 12..17 -> R.string.today_greeting_afternoon
    else -> R.string.today_greeting_evening
}

/**
 * Formats the card's date as "M/d (E)" in the default locale, so the weekday
 * localizes automatically (e.g. "6/13 (週五)" / "6/13 (Fri)"). The literal date
 * pattern is layout, not user-facing copy.
 */
private fun formatToday(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.getDefault()))

/** Reading-time formatter (HH:mm, system zone). */
private fun timeFmt(): DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

/** Pro-tip body selected by the reading's BP classification (member's guideline). */
private fun proTipRes(systolic: Int, diastolic: Int, guideline: HypertensionGuideline): Int =
    when (classify(systolic, diastolic, guideline)) {
        BpCategory.Normal -> R.string.today_protip_normal
        BpCategory.Elevated -> R.string.today_protip_elevated
        BpCategory.Stage1 -> R.string.today_protip_stage1
        BpCategory.Stage2 -> R.string.today_protip_stage2
        BpCategory.HypertensiveCrisis -> R.string.today_protip_crisis
        BpCategory.Hypotension -> R.string.today_protip_hypotension
    }
