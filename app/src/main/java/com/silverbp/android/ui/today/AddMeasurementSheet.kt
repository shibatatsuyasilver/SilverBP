package com.silverbp.android.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.BpRedSbp
import com.silverbp.android.ui.theme.CategoryHypotension
import com.silverbp.android.ui.theme.CategoryNormal

/**
 * The "+" record chooser shown over [TodayScreen]: a day's record now covers
 * blood pressure, blood glucose and weight, so the top-right add button opens
 * this sheet to pick which to capture instead of jumping straight into the BP
 * camera.
 *
 * Self-contained — the host only owns the [visible] flag (set true on the "+"
 * onClick) and supplies the capture callbacks; this composable renders
 * nothing while [visible] is false and dismisses itself on selection or scrim
 * tap. Mirrors the [com.silverbp.android.ui.member.MemberEditorSheet]
 * ModalBottomSheet idiom. Every option carries a contentDescription (audit M31).
 *
 * Visual: each option is a tappable surface card carrying a tinted type-icon
 * tile (heart / drop / scale), matching the Today/UnifiedHistory card family.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMeasurementSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCaptureBp: () -> Unit,
    onCaptureGlucose: () -> Unit,
    onAddWeight: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AppSpacing.screenH)
                .padding(bottom = AppSpacing.screenV + AppSpacing.itemGap),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            Text(
                stringResource(R.string.add_measure_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    start = AppSpacing.tight,
                    bottom = AppSpacing.itemGap,
                ),
            )

            // 量血壓 → BP capture. Dismiss first so the sheet's exit animation
            // doesn't race the navigation push.
            AddMeasurementRow(
                icon = Icons.Filled.MonitorHeart,
                tint = BpRedSbp,
                label = stringResource(R.string.measure_bp),
                contentDescription = stringResource(R.string.add_measure_bp_cd),
                onClick = {
                    onDismiss()
                    onCaptureBp()
                },
            )

            // 量血糖 → glucose capture (manual entry is the always-available path).
            AddMeasurementRow(
                icon = Icons.Filled.Bloodtype,
                tint = CategoryHypotension,
                label = stringResource(R.string.measure_glucose),
                contentDescription = stringResource(R.string.add_measure_glucose_cd),
                onClick = {
                    onDismiss()
                    onCaptureGlucose()
                },
            )

            // 量體重 → manual weight entry (no camera this phase). The CTA label
            // doubles as the contentDescription — it already reads as the action.
            AddMeasurementRow(
                icon = Icons.Filled.MonitorWeight,
                tint = CategoryNormal,
                label = stringResource(R.string.weight_log_cta),
                contentDescription = stringResource(R.string.weight_log_cta),
                onClick = {
                    onDismiss()
                    onAddWeight()
                },
            )
        }
    }
}

/**
 * One chooser option: a tappable surface card with a leading tinted type-icon
 * tile (matching the Today/UnifiedHistory timeline rows), the option label, and
 * a trailing chevron. Pure UI — the click wiring is handed in by the caller.
 */
@Composable
private fun AddMeasurementRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.cardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = onClick)
                .semantics { this.contentDescription = contentDescription }
                .padding(horizontal = AppSpacing.cardPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LEADING: tinted type-icon tile.
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
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.size(AppSpacing.screenH))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
