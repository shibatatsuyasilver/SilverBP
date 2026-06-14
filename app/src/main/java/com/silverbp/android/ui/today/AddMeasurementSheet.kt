package com.silverbp.android.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R

/**
 * The "+" record chooser shown over [TodayScreen]: a day's record now covers
 * blood pressure, blood glucose, and body weight, so the top-right add button
 * opens this sheet to pick which to capture instead of jumping straight into the
 * BP camera.
 *
 * Self-contained — the host only owns the [visible] flag (set true on the "+"
 * onClick) and supplies the three capture callbacks; this composable renders
 * nothing while [visible] is false and dismisses itself on selection or scrim
 * tap. Mirrors the [com.silverbp.android.ui.member.MemberEditorSheet]
 * ModalBottomSheet idiom. All options carry a contentDescription (audit M31).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMeasurementSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCaptureBp: () -> Unit,
    onCaptureGlucose: () -> Unit,
    onCaptureWeight: () -> Unit = {},
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.add_measure_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // 量血壓 → BP capture. Dismiss first so the sheet's exit animation
            // doesn't race the navigation push.
            AddMeasurementRow(
                icon = Icons.Filled.MonitorHeart,
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
                label = stringResource(R.string.measure_glucose),
                contentDescription = stringResource(R.string.add_measure_glucose_cd),
                onClick = {
                    onDismiss()
                    onCaptureGlucose()
                },
            )

            // 量體重 → weight entry (manual only this round; scale OCR is backlog).
            AddMeasurementRow(
                icon = Icons.Filled.MonitorWeight,
                label = stringResource(R.string.measure_weight),
                contentDescription = stringResource(R.string.add_measure_weight_cd),
                onClick = {
                    onDismiss()
                    onCaptureWeight()
                },
            )
        }
    }
}

@Composable
private fun AddMeasurementRow(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
