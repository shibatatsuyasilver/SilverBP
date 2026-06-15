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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.silverbp.android.core.WeightGuideline
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.ui.member.MemberSwitcherChip
import com.silverbp.android.ui.components.BpReadingValue
import com.silverbp.android.ui.components.ModelLoadBanner
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.categoryShortLabel
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
import com.silverbp.android.ui.components.formatGlucoseValue
import com.silverbp.android.ui.components.glucoseCategoryLabel
import com.silverbp.android.ui.components.glucoseColorFor
import com.silverbp.android.ui.components.glucoseUnitLabel
import com.silverbp.android.ui.components.weightCategoryLabel
import com.silverbp.android.ui.components.weightColorFor
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.ForgePrimary
import com.silverbp.android.ui.theme.PrimaryDark
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
    // Weight section (Phase 2, manual entry only). onLogWeight opens manual
    // logging (WEIGHT_CONFIRM_NEW); onEditWeight edits the shown reading via the
    // Confirm flow; onViewWeightHistory opens the weight history list. Defaults
    // are no-ops so AppNavHost compiles until the nav track wires these routes.
    onLogWeight: () -> Unit = {},
    onEditWeight: (String) -> Unit = {},
    onViewWeightHistory: () -> Unit = {},
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
            onAddWeight = onLogWeight,
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
                    // BP HERO — today's latest reading as a vivid indigo-gradient
                    // card (or a friendly inline "記一筆" prompt when none today).
                    // Inline prompts reuse the capture callbacks (the same entry
                    // points the "+" chooser offers).
                    BpHeroCard(
                        readings = state.todayBp,
                        guideline = state.guideline,
                        onRecord = onCaptureBp,
                        onEdit = onEditBp,
                        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                    )

                    // 血糖 + 體重 as two compact, equal-weight cards side by side.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.screenH),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
                    ) {
                        GlucoseMiniCard(
                            readings = state.todayGlucose,
                            unit = state.glucoseUnit,
                            onRecord = onCaptureGlucose,
                            onEdit = onEditGlucose,
                            onViewHistory = onViewGlucoseHistory,
                            modifier = Modifier.weight(1f),
                        )
                        WeightMiniCard(
                            latest = state.latestWeight,
                            bmi = state.weightBmi,
                            onRecord = onLogWeight,
                            onEdit = onEditWeight,
                            onViewHistory = onViewWeightHistory,
                            modifier = Modifier.weight(1f),
                        )
                    }

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
 * BP HERO — today's latest blood-pressure reading as a vivid indigo-gradient
 * card (ForgePrimary → PrimaryDark), mirroring the iOS `LatestReadingCard`:
 * a "血壓 · {time}" label top-left, a filled category chip (white pill with a
 * category dot + short label) top-right, the big "S / D mmHg" number in white,
 * and a "脈搏 N" pill. Tapping the card edits the reading via [onEdit].
 *
 * When there's no BP today the card becomes a friendly inline prompt that keeps
 * the existing "記一筆" affordance ([onRecord]).
 */
@Composable
private fun BpHeroCard(
    readings: List<BpReading>,
    guideline: HypertensionGuideline,
    onRecord: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = readings.maxByOrNull { it.timestamp }
    if (latest == null) {
        BpHeroEmptyCard(onRecord = onRecord, modifier = modifier)
        return
    }

    val cat = classify(latest.systolic, latest.diastolic, guideline)
    val dotColor = colorFor(cat)
    val editCd = stringResource(R.string.today_edit_bp_a11y)
    val onHero = Color.White
    val onHeroDim = Color.White.copy(alpha = 0.85f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.heroCorner))
            .background(
                Brush.linearGradient(colors = listOf(ForgePrimary, PrimaryDark)),
            )
            .clickable { onEdit(latest.id.toString()) }
            .semantics { contentDescription = editCd }
            .padding(AppSpacing.cardPadding),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
            // Label + category chip.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${stringResource(R.string.today_section_bp)} · ${timeFmt().format(latest.timestamp)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = onHeroDim,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.20f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColor))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        categoryShortLabel(cat),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onHero,
                    )
                }
            }
            // Big numbers (white, via the value component's colour overrides).
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BpReadingValue(
                    systolic = latest.systolic,
                    diastolic = latest.diastolic,
                    sbpColor = onHero,
                    dbpColor = onHero,
                    separatorColor = onHeroDim,
                )
                Text(
                    stringResource(R.string.mmhg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onHeroDim,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            // Pulse pill.
            latest.pulse?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = onHero,
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        "$it ${stringResource(R.string.bpm)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = onHero,
                    )
                }
            }
        }
    }
}

/** Friendly "今天還沒記錄 · 記一筆" placeholder occupying the BP hero's slot. */
@Composable
private fun BpHeroEmptyCard(
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recordCd = stringResource(R.string.today_record_bp_a11y)
    StandardCard(
        modifier = modifier,
        cornerRadius = AppSpacing.heroCorner,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ForgePrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MonitorHeart,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ForgePrimary,
                )
            }
            Spacer(Modifier.size(AppSpacing.itemGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.today_section_bp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.today_empty_today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onRecord,
                modifier = Modifier.semantics { contentDescription = recordCd },
            ) {
                Text(stringResource(R.string.today_record_one))
            }
        }
    }
}

/**
 * 血糖 compact card — today's latest glucose reading (or an inline "記一筆"
 * prompt) styled as a [MetricMiniCard]: a tinted drop-icon tile, the value +
 * unit, and the glucose category dot + label. The "今天 N 筆" affordance (a card
 * with more than one reading today) opens glucose history via [onViewHistory].
 */
@Composable
private fun GlucoseMiniCard(
    readings: List<GlucoseReading>,
    unit: GlucoseUnit,
    onRecord: () -> Unit,
    onEdit: (String) -> Unit,
    onViewHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = readings.maxByOrNull { it.timestamp }
    if (latest == null) {
        MetricMiniEmptyCard(
            icon = Icons.Filled.Bloodtype,
            tint = CategoryStage2Tint(),
            title = stringResource(R.string.today_section_glucose),
            onRecord = onRecord,
            recordCd = stringResource(R.string.today_record_glucose_a11y),
            modifier = modifier,
        )
        return
    }
    val cat = remember(latest) {
        GlucoseClassifier().classify(latest.valueMgdl, latest.measureContext)
    }
    MetricMiniCard(
        icon = Icons.Filled.Bloodtype,
        tint = glucoseColorFor(cat),
        title = stringResource(R.string.today_section_glucose),
        count = readings.size,
        value = formatGlucoseValue(latest.valueMgdl, unit),
        unit = glucoseUnitLabel(unit),
        categoryLabel = glucoseCategoryLabel(cat),
        categoryColor = glucoseColorFor(cat),
        editCd = stringResource(R.string.today_edit_glucose_a11y),
        onEdit = { onEdit(latest.id.toString()) },
        onViewHistory = onViewHistory,
        modifier = modifier,
    )
}

/**
 * 體重 compact card — the member's most-recent weight reading (latest-ever, not
 * today-scoped; see [TodayUiState.latestWeight]) styled as a [MetricMiniCard]:
 * a tinted scale-icon tile, the value + unit in the unit it was captured in
 * ([WeightReading.displayUnit]), and the BMI band dot + label when the member's
 * profile height is known. Manual entry only this phase: the empty state opens
 * manual logging via [onRecord]; the value taps through to edit via [onEdit].
 */
@Composable
private fun WeightMiniCard(
    latest: WeightReading?,
    bmi: Double?,
    onRecord: () -> Unit,
    onEdit: (String) -> Unit,
    onViewHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (latest == null) {
        MetricMiniEmptyCard(
            icon = Icons.Filled.MonitorWeight,
            tint = CategoryNormalTint(),
            title = stringResource(R.string.weight_title),
            onRecord = onRecord,
            recordCd = stringResource(R.string.weight_log_cta),
            modifier = modifier,
        )
        return
    }
    // The reading carries the unit it was entered in; read the canonical kg value
    // back in that unit for display (the user sees the same number they saved).
    // BMI is precomputed in the VM from the member's height.
    val unit = latest.displayUnit
    val category = bmi?.let { WeightGuideline.classify(it) }
    val color = category?.let { weightColorFor(it) }
    MetricMiniCard(
        icon = Icons.Filled.MonitorWeight,
        tint = color ?: MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.weight_title),
        count = 0,
        value = formatWeightValue(latest.valueIn(unit)),
        unit = weightUnitLabel(unit),
        categoryLabel = category?.let { weightCategoryLabel(it) }
            ?: bmi?.let { "${stringResource(R.string.weight_bmi_label)} ${formatBmi(it)}" },
        categoryColor = color,
        editCd = stringResource(R.string.weight_confirm_title),
        onEdit = { onEdit(latest.id.toString()) },
        onViewHistory = onViewHistory,
        modifier = modifier,
    )
}

/**
 * Shared compact metric card used for 血糖 / 體重 — a [StandardCard] with a
 * leading tinted type-icon tile, the section title (+ a "今天 N 筆" affordance
 * when [count] > 1), the big value + unit, and a category dot + label. Mirrors
 * the iOS `MergedTimelineRow` / side-by-side latest cards. File-private.
 */
@Composable
private fun MetricMiniCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    count: Int,
    value: String,
    unit: String,
    categoryLabel: String?,
    categoryColor: Color?,
    editCd: String,
    onEdit: () -> Unit,
    onViewHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StandardCard(
        modifier = modifier
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(AppSpacing.cardCorner))
            .clickable { onEdit() }
            .semantics { contentDescription = editCd },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        // Tinted type-icon tile.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = tint,
            )
        }
        // Title (+ "今天 N 筆" affordance when there's more than one today).
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
        // Big value + unit.
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                unit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // Category dot + label.
        if (categoryLabel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(categoryColor ?: MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    categoryLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Inline "記一筆" placeholder for an empty 血糖 / 體重 compact card. */
@Composable
private fun MetricMiniEmptyCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    onRecord: () -> Unit,
    recordCd: String,
    modifier: Modifier = Modifier,
) {
    StandardCard(
        modifier = modifier.heightIn(min = 150.dp),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = tint,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** Default glucose-tile tint when there's no reading to classify (diabetes red). */
@Composable
private fun CategoryStage2Tint(): Color = glucoseColorFor(com.silverbp.android.core.GlucoseCategory.High)

/** Default weight-tile tint when there's no reading / BMI (normal-band green). */
@Composable
private fun CategoryNormalTint(): Color = weightColorFor(com.silverbp.android.core.WeightCategory.Normal)

/** Time-of-day greeting chosen from the local hour. */
private fun greetingRes(): Int = when (LocalTime.now().hour) {
    in 0 until 12 -> R.string.today_greeting_morning
    in 12..17 -> R.string.today_greeting_afternoon
    else -> R.string.today_greeting_evening
}

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

/** Localized unit label (公斤 / 磅) for a [WeightUnit]. */
@Composable
private fun weightUnitLabel(unit: WeightUnit): String = stringResource(
    when (unit) {
        WeightUnit.Kg -> R.string.weight_unit_kg
        WeightUnit.Lb -> R.string.weight_unit_lb
    },
)

/** Weight value to one decimal place (e.g. "68.5") — both kg and lb read naturally. */
private fun formatWeightValue(value: Double): String = "%.1f".format(value)

/** BMI to one decimal place (e.g. "22.4"). */
private fun formatBmi(bmi: Double): String = "%.1f".format(bmi)
