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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.silverbp.android.core.WeightGuideline
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.ui.member.MemberSwitcherChip
import com.silverbp.android.ui.components.BpReadingValue
import com.silverbp.android.ui.components.HeroCard
import com.silverbp.android.ui.components.HeroForeground
import com.silverbp.android.ui.components.HeroForegroundDim
import com.silverbp.android.ui.components.HeroLabel
import com.silverbp.android.ui.components.HeroPulsePill
import com.silverbp.android.ui.components.HeroStatusPill
import com.silverbp.android.ui.components.MetricCard
import com.silverbp.android.ui.components.MetricCardEmpty
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
import com.silverbp.android.ui.theme.MetricAccent
import com.silverbp.android.ui.theme.PrimaryDark
import java.time.LocalTime

/**
 * Today tab. Owner decision §1/§2: a single unified daily-record card titled with
 * today's date holds an equal-footing **blood-pressure** section and a **blood-
 * glucose** section, each showing today's latest reading (or an inline "今天還沒
 * 記錄 · 記一筆" prompt). Replaces the old latest-ever BP hero + separate glucose
 * card. Today-scoped via [TodayViewModel] (system-tz calendar day), member-scoped
 * exactly like the old cards.
 *
 * Adding a reading lives on each card: every section shows a "記一筆" affordance
 * in BOTH its empty state and its populated state ([onCaptureBp] opens BP capture,
 * [onCaptureGlucose] the meter-capture flow, [onLogWeight] weight logging) — there
 * is no longer a top-bar "+" chooser. Tapping a shown reading edits it via
 * [onEditBp] / [onEditGlucose] (the existing Confirm edit routes; id as a string).
 * The "今天 N 筆" affordance (a section with >1 reading today) opens that type's
 * history via [onViewBpHistory] / [onViewGlucoseHistory].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    // Each card carries its own "記一筆" in both empty and populated states (no
    // top-bar "+" chooser): onCaptureBp opens the BP camera; onCaptureGlucose
    // opens the meter-capture flow. The per-card inline "記一筆" prompts — empty
    // and populated alike — reuse these same callbacks.
    onCaptureBp: () -> Unit,
    onCaptureGlucose: () -> Unit,
    onAddManual: () -> Unit,
    onOpenSettings: () -> Unit,
    // Premium entry: the lime crown in the top bar opens the full Premium page.
    // Default no-op so previews / partial wiring still compile.
    onOpenPremium: () -> Unit = {},
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
    // Medication module on Today (owner UX: meds were too buried in Settings).
    // onManageMedications opens the manage/edit list; onAddMedication jumps
    // straight to the new-medication editor for the first-run empty CTA. Defaults
    // are no-ops so previews / partial wiring still compile.
    onManageMedications: () -> Unit = {},
    onAddMedication: () -> Unit = {},
    vm: TodayViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

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
                    // Premium entry — gold crown, stands out against the dark bar.
                    IconButton(onClick = onOpenPremium) {
                        Icon(
                            Icons.Filled.WorkspacePremium,
                            contentDescription = stringResource(R.string.premium_entry_a11y),
                            tint = com.silverbp.android.ui.theme.PremiumGold,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            ModelLoadBanner(phase = state.modelPhase)
            Spacer(Modifier.height(AppSpacing.tight))

            val greeting = stringResource(greetingRes())
            val headerText = if (state.userName.isNotBlank()) {
                stringResource(R.string.today_greeting_named, greeting, state.userName)
            } else {
                greeting
            }
            Text(
                text = headerText,
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
                    // Both states carry a "記一筆" affordance; it reuses the BP
                    // capture callback (onCaptureBp).
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
                            weightCount = state.weightCount,
                            onRecord = onLogWeight,
                            onEdit = onEditWeight,
                            onViewHistory = onViewWeightHistory,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Medication module — Apple Health-style "用藥" card. First-run
                    // shows an "add medication" CTA; once set up it lists today's
                    // doses with check-circles. Placed below the daily readings so
                    // it reads as the day's to-do after the numbers.
                    TodayMedicationCard(
                        meds = state.todayMeds,
                        hasMedications = state.hasMedications,
                        onToggle = vm::toggleDose,
                        onManage = onManageMedications,
                        onAdd = onAddMedication,
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
    val recordCd = stringResource(R.string.today_record_bp_a11y)
    HeroCard(
        modifier = modifier
            .clickable { onEdit(latest.id.toString()) }
            .semantics { contentDescription = editCd },
    ) {
        // Label + category chip. Per-reading time is intentionally not shown on
        // Today cards (owner decision) — the section is today-scoped, so the label
        // is just the section name with the category chip trailing.
        HeroLabel(
            text = stringResource(R.string.today_section_bp),
            trailing = {
                HeroStatusPill(text = categoryShortLabel(cat), dotColor = dotColor)
            },
        )
        // Big numbers (white, via the value component's colour overrides).
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BpReadingValue(
                systolic = latest.systolic,
                diastolic = latest.diastolic,
                sbpColor = HeroForeground,
                dbpColor = HeroForeground,
                separatorColor = HeroForegroundDim,
            )
            Text(
                stringResource(R.string.mmhg),
                style = MaterialTheme.typography.bodyMedium,
                color = HeroForegroundDim,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        // Pulse pill.
        latest.pulse?.let {
            HeroPulsePill(text = "$it ${stringResource(R.string.bpm)}")
        }
        // Always-available "記一筆" — reuses the empty-hero pill styling (white
        // CircleShape + PrimaryDark text) so 記一筆 is present in BOTH states.
        // Right-aligned at the foot; a child clickable whose tap is consumed, so
        // the whole-card edit onClick does not also fire.
        Text(
            text = stringResource(R.string.today_record_one),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = PrimaryDark,
            modifier = Modifier
                .align(Alignment.End)
                .clip(CircleShape)
                .background(HeroForeground)
                .clickable(onClick = onRecord)
                .semantics { contentDescription = recordCd }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/**
 * Friendly "今天還沒記錄 · 記一筆" placeholder that KEEPS the BP hero's identity:
 * the same indigo-gradient [HeroCard] as the populated hero, not a plain bar. This
 * is the §-design fix — the empty state used to collapse into a [StandardCard] row,
 * which clashed with the compact glucose/weight cards below ("three different
 * shapes"). Now the hero stays the hero in both states: a "血壓" label up top and a
 * "今天還沒記錄" line with a white "記一筆" pill ([onRecord]) at the foot.
 */
@Composable
private fun BpHeroEmptyCard(
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recordCd = stringResource(R.string.today_record_bp_a11y)
    HeroCard(modifier = modifier) {
        HeroLabel(text = stringResource(R.string.today_section_bp))
        // A little height so the empty hero reads as a hero block, not a thin bar.
        Spacer(Modifier.height(AppSpacing.itemGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.today_empty_today),
                style = MaterialTheme.typography.titleMedium,
                color = HeroForegroundDim,
                modifier = Modifier.weight(1f),
            )
            // White "記一筆" pill — dark-indigo text on white reads clearly on the
            // gradient and mirrors the populated hero's white status chip.
            Text(
                text = stringResource(R.string.today_record_one),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryDark,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(HeroForeground)
                    .clickable(onClick = onRecord)
                    .semantics { contentDescription = recordCd }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
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
        val recordCd = stringResource(R.string.today_record_glucose_a11y)
        MetricCardEmpty(
            icon = Icons.Filled.Bloodtype,
            accent = MetricAccent.Glucose,
            title = stringResource(R.string.today_section_glucose),
            ctaText = stringResource(R.string.today_record_one),
            onClick = onRecord,
            modifier = modifier.semantics { contentDescription = recordCd },
        )
        return
    }
    val cat = remember(latest) {
        GlucoseClassifier().classify(latest.valueMgdl, latest.measureContext)
    }
    val editCd = stringResource(R.string.today_edit_glucose_a11y)
    val addCd = stringResource(R.string.today_record_glucose_a11y)
    val addLabel = stringResource(R.string.today_record_one)
    val multi = readings.size > 1
    // Per-reading time is not shown on Today cards. The trailing slot now only
    // carries the multi → history affordance ("今天 N 筆"); single readings show
    // nothing there (today-scoped, so the reading is definitionally today).
    val timeLabel = if (multi) stringResource(R.string.today_count_today, readings.size) else null
    // The icon tile uses the FIXED glucose accent; the reading's category drives
    // only the dot + label colour (glucoseColorFor). When there are multiple
    // readings today the card opens history (the old "今天 N 筆" affordance);
    // otherwise it edits the shown reading (the old card-tap behaviour).
    MetricCard(
        icon = Icons.Filled.Bloodtype,
        accent = MetricAccent.Glucose,
        title = stringResource(R.string.today_section_glucose),
        value = formatGlucoseValue(latest.valueMgdl, unit),
        unit = glucoseUnitLabel(unit),
        time = timeLabel,
        categoryColor = glucoseColorFor(cat),
        categoryLabel = glucoseCategoryLabel(cat),
        onClick = { onEdit(latest.id.toString()) },
        onTimeClick = if (multi) onViewHistory else null,
        onAddAnother = onRecord,
        addLabel = addLabel,
        addCd = addCd,
        modifier = modifier.semantics { contentDescription = editCd },
    )
}

/**
 * 體重 compact card — today's latest weigh-in (today-scoped like 血壓/血糖; see
 * [TodayUiState.latestWeight]) styled as a [MetricMiniCard]: a tinted scale-icon
 * tile, the value + unit in the unit it was captured in ([WeightReading.displayUnit]),
 * and the BMI band dot + label when the member's profile height is known. When
 * there's no weigh-in today the empty state opens manual logging via [onRecord];
 * the value taps through to edit via [onEdit]; >1 weigh-in today shows the
 * "今天 N 筆" history affordance via [onViewHistory].
 */
@Composable
private fun WeightMiniCard(
    latest: WeightReading?,
    bmi: Double?,
    weightCount: Int,
    onRecord: () -> Unit,
    onEdit: (String) -> Unit,
    onViewHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (latest == null) {
        MetricCardEmpty(
            icon = Icons.Filled.MonitorWeight,
            accent = MetricAccent.Weight,
            title = stringResource(R.string.weight_title),
            ctaText = stringResource(R.string.today_record_one),
            onClick = onRecord,
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
    val editCd = stringResource(R.string.weight_confirm_title)
    val addCd = stringResource(R.string.today_record_weight_a11y)
    val addLabel = stringResource(R.string.today_record_one)
    val multi = weightCount > 1
    // Per-reading time is not shown on Today cards. Weight is today-scoped now, so
    // the trailing slot mirrors 血糖: "今天 N 筆" (→ history) when there's more than
    // one weigh-in today; a single weigh-in shows nothing there.
    val timeLabel = if (multi) stringResource(R.string.today_count_today, weightCount) else null
    // The icon tile uses the FIXED weight accent; the BMI band drives only the
    // dot + label colour (weightColorFor). When there's more than one weigh-in
    // today the card opens history (the "今天 N 筆" affordance); otherwise it edits
    // the shown reading (the card-tap behaviour).
    MetricCard(
        icon = Icons.Filled.MonitorWeight,
        accent = MetricAccent.Weight,
        title = stringResource(R.string.weight_title),
        value = formatWeightValue(latest.valueIn(unit)),
        unit = weightUnitLabel(unit),
        time = timeLabel,
        categoryColor = color,
        categoryLabel = category?.let { weightCategoryLabel(it) }
            ?: bmi?.let { "${stringResource(R.string.weight_bmi_label)} ${formatBmi(it)}" },
        onClick = { onEdit(latest.id.toString()) },
        onTimeClick = if (multi) onViewHistory else null,
        onAddAnother = onRecord,
        addLabel = addLabel,
        addCd = addCd,
        modifier = modifier.semantics { contentDescription = editCd },
    )
}

/** Time-of-day greeting chosen from the local hour. */
private fun greetingRes(): Int = when (LocalTime.now().hour) {
    in 0 until 12 -> R.string.today_greeting_morning
    in 12..17 -> R.string.today_greeting_afternoon
    else -> R.string.today_greeting_evening
}

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
