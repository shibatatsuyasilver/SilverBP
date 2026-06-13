package com.silverbp.android.ui.today

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Bloodtype
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onCapture: () -> Unit,
    onAddManual: () -> Unit,
    onOpenSettings: () -> Unit,
    // Default no-op so AppNavHost compiles unchanged until it wires the
    // MEMBER_MANAGE navigation (the chip self-hides for single-member installs).
    onManageMembers: () -> Unit = {},
    // Default no-op so AppNavHost compiles unchanged until the capture/confirm
    // track wires the glucose confirm route.
    onRecordGlucose: () -> Unit = {},
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
                    IconButton(onClick = onCapture) {
                        Icon(
                            Icons.Filled.AddCircle,
                            contentDescription = stringResource(R.string.add_reading_a11y),
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
                state.latest == null -> EmptyTodayState()
                else -> {
                    val reading = state.latest!!
                    LatestReadingCard(reading, state.guideline, modifier = Modifier.padding(horizontal = AppSpacing.screenH))

                    Text(
                        text = stringResource(R.string.today_readings_logged, state.totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                    )

                    StandardCard(
                        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                        title = stringResource(R.string.today_protip_title),
                    ) {
                        Text(
                            text = stringResource(proTipRes(reading.systolic, reading.diastolic, state.guideline)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(AppSpacing.sectionGap))
                }
            }

            // Glucose card (v19): follows the selected member exactly like the BP
            // card. Shown below the BP content (and below the empty-BP state) so it
            // never displaces the primary BP surface; hidden while loading/error.
            if (!state.isLoading && !state.error) {
                GlucoseCard(
                    reading = state.latestGlucose,
                    unit = state.glucoseUnit,
                    onRecordGlucose = onRecordGlucose,
                    modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                )
                Spacer(Modifier.height(AppSpacing.sectionGap))
            }
        }
    }
}

/**
 * Today's glucose card: the selected member's most recent reading (value in the
 * user's unit + category colour + timing) and a "log glucose" button. Empty state
 * when the member has no readings. Parallels [LatestReadingCard] (BP).
 */
@Composable
private fun GlucoseCard(
    reading: GlucoseReading?,
    unit: GlucoseUnit,
    onRecordGlucose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StandardCard(
        modifier = modifier,
        title = stringResource(R.string.glucose_title),
        titleTrailing = {
            TextButton(onClick = onRecordGlucose) {
                Text(stringResource(R.string.glucose_record))
            }
        },
    ) {
        if (reading == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bloodtype,
                    contentDescription = stringResource(R.string.glucose_card_cd),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.glucose_card_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val cat = remember(reading) {
                GlucoseClassifier().classify(reading.valueMgdl, reading.measureContext)
            }
            val color = glucoseColorFor(cat)
            val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.TAIWAN)
                .withZone(ZoneId.systemDefault())

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.size(8.dp))
                Text(
                    glucoseCategoryLabel(cat),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatGlucoseValue(reading.valueMgdl, unit),
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
                "${measureContextLabel(reading.measureContext)} · ${fmt.format(reading.timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyTodayState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.MonitorHeart,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.no_readings),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.capture_cta),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LatestReadingCard(
    reading: BpReading,
    guideline: HypertensionGuideline,
    modifier: Modifier = Modifier,
) {
    val cat = classify(reading.systolic, reading.diastolic, guideline)
    val color = colorFor(cat)
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.TAIWAN).withZone(zone)

    StandardCard(
        modifier = modifier,
        cornerRadius = AppSpacing.heroCorner,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
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

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BpReadingValue(systolic = reading.systolic, diastolic = reading.diastolic)
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.mmhg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        reading.pulse?.let {
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
            fmt.format(reading.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
