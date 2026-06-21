package com.silverbp.android.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.core.db.MedicationKind
import com.silverbp.android.ui.components.CheckCircle
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.CategoryNormal
import com.silverbp.android.ui.theme.ForgePrimary

/**
 * Today's medication module — an Apple Health-style "Medications" card surfaced
 * on the home screen so first-time users discover medication tracking without
 * digging into Settings (owner UX decision: meds were too buried). Three states:
 *
 *  1. No medications saved → a first-run CTA ("add medication") into the meds
 *     editor ([onAdd]).
 *  2. Has medications, none due today → a compact card with a "manage" link
 *     ([onManage]) so the entry stays visible.
 *  3. Doses due today → today's doses grouped by part-of-day with an adherence
 *     progress bar; each row's [CheckCircle] toggles taken state via [onToggle]
 *     (same deterministic dose row as the notification action / log screen).
 *
 * Medication purple ([ForgePrimary]) matches the existing meds screens. Pure UI:
 * state is owned by [TodayViewModel] ([TodayUiState.todayMeds] /
 * [TodayUiState.hasMedications]).
 */
@Composable
fun TodayMedicationCard(
    meds: List<TodayMedDose>,
    hasMedications: Boolean,
    onToggle: (TodayMedDose, Boolean) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !hasMedications -> MedEmptyCard(onAdd = onAdd, modifier = modifier)
        meds.isEmpty() -> MedNoDosesCard(onManage = onManage, modifier = modifier)
        else -> MedDosesCard(meds = meds, onToggle = onToggle, onManage = onManage, modifier = modifier)
    }
}

/** First-run state: a friendly prompt + lime CTA into the medication editor. */
@Composable
private fun MedEmptyCard(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    StandardCard(modifier = modifier, cornerRadius = AppSpacing.heroCorner) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillTile(size = 46.dp)
            Spacer(Modifier.size(AppSpacing.itemGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.today_meds_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.today_meds_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ExpressiveSecondaryButton(
            text = stringResource(R.string.medication_add),
            onClick = onAdd,
            icon = Icons.Filled.Add,
            fillWidth = true,
        )
    }
}

/** Has-meds-but-none-today state: keeps the management entry on the home screen. */
@Composable
private fun MedNoDosesCard(onManage: () -> Unit, modifier: Modifier = Modifier) {
    StandardCard(
        modifier = modifier,
        title = stringResource(R.string.today_meds_title),
        titleTrailing = { ManageLink(onManage) },
    ) {
        Text(
            stringResource(R.string.medication_log_no_today_doses),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Doses-due-today state: adherence progress + part-of-day grouped dose rows. */
@Composable
private fun MedDosesCard(
    meds: List<TodayMedDose>,
    onToggle: (TodayMedDose, Boolean) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StandardCard(
        modifier = modifier,
        title = stringResource(R.string.today_meds_title),
        titleTrailing = { ManageLink(onManage) },
    ) {
        val takenCount = meds.count { it.taken }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            ProgressBar(
                fraction = takenCount.toFloat() / meds.size,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.today_meds_progress, takenCount, meds.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // meds arrive sorted by time; group into part-of-day buckets (groupBy
        // preserves encounter order, so each bucket stays time-sorted) and render
        // each non-empty bucket under its header, in PartOfDay declaration order.
        val grouped = meds.groupBy { PartOfDay.of(it.hour) }
        PartOfDay.values().forEach { pod ->
            val rows = grouped[pod] ?: return@forEach
            Text(
                stringResource(pod.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AppSpacing.tight),
            )
            rows.forEach { dose -> MedRow(dose = dose, onToggle = onToggle) }
        }
    }
}

@Composable
private fun MedRow(dose: TodayMedDose, onToggle: (TodayMedDose, Boolean) -> Unit) {
    val time = "%02d:%02d".format(dose.hour, dose.minute)
    val kindLabel = if (dose.kind == MedicationKind.SUPPLEMENT) {
        stringResource(R.string.medication_kind_supplement)
    } else {
        null
    }
    val subtitle = listOfNotNull(dose.dose.ifBlank { null }, time, kindLabel).joinToString(" · ")
    val checkCd = if (dose.taken) {
        "${dose.name} ${stringResource(R.string.coach_log_medication_taken)}"
    } else {
        "${dose.name} ${stringResource(R.string.medication_action_taken)}"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillTile(size = 44.dp)
        Spacer(Modifier.size(AppSpacing.itemGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                dose.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // Dim the name once taken, mirroring Apple Health's logged rows.
                color = if (dose.taken) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(AppSpacing.tight))
        CheckCircle(
            checked = dose.taken,
            onCheckedChange = { onToggle(dose, it) },
            contentDescription = checkCd,
        )
    }
}

/** "管理 ›" affordance for the card title row → the medication manage screen. */
@Composable
private fun ManageLink(onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onManage)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.medication_manage_action),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Slim rounded adherence bar (green fill over a muted track). */
@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(CategoryNormal),
        )
    }
}

/** The medication accent tile: purple pill glyph on a low-alpha purple square. */
@Composable
private fun PillTile(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(ForgePrimary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Medication,
            contentDescription = null,
            tint = ForgePrimary,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** Apple Health-style time-of-day grouping for today's doses. */
private enum class PartOfDay(val labelRes: Int) {
    Morning(R.string.today_meds_morning),
    Afternoon(R.string.today_meds_afternoon),
    Evening(R.string.today_meds_evening);

    companion object {
        fun of(hour: Int): PartOfDay = when {
            hour < 12 -> Morning
            hour < 18 -> Afternoon
            else -> Evening
        }
    }
}
