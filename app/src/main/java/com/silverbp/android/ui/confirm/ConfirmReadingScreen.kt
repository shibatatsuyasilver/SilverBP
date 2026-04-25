package com.silverbp.android.ui.confirm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.Arm
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.core.Posture

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmReadingScreen(
    readingIdArg: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: ConfirmReadingViewModel = viewModel(),
) {
    LaunchedEffect(readingIdArg) { vm.initWith(readingIdArg) }
    val draft by vm.draft.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.confirm)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, null) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            draft.photo?.let { bmp ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            ConfidenceBar(draft.confidence)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.systolic_full), draft.systolic.toString(),
                    onAccept = { v -> vm.update { it.copy(systolic = v) } }, modifier = Modifier.weight(1f))
                NumberField(stringResource(R.string.diastolic_full), draft.diastolic.toString(),
                    onAccept = { v -> vm.update { it.copy(diastolic = v) } }, modifier = Modifier.weight(1f))
                NumberField(stringResource(R.string.pulse), (draft.pulse ?: 0).toString(),
                    onAccept = { v -> vm.update { it.copy(pulse = v.takeIf { it > 0 }) } }, modifier = Modifier.weight(1f))
            }

            ChipRow("時段", listOf(
                PartOfDay.Morning to stringResource(R.string.part_morning),
                PartOfDay.Evening to stringResource(R.string.part_evening),
            ), draft.partOfDay) { p -> vm.update { it.copy(partOfDay = p) } }

            ChipRow("手臂", listOf(
                Arm.Left to stringResource(R.string.arm_left),
                Arm.Right to stringResource(R.string.arm_right),
            ), draft.arm) { a -> vm.update { it.copy(arm = a) } }

            ChipRow("姿勢", listOf(
                Posture.Sitting to stringResource(R.string.posture_sitting),
                Posture.Supine to stringResource(R.string.posture_supine),
                Posture.Standing to stringResource(R.string.posture_standing),
            ), draft.posture) { p -> vm.update { it.copy(posture = p) } }

            SwitchRow(stringResource(R.string.before_medication), draft.beforeMedication) { v -> vm.update { it.copy(beforeMedication = v) } }
            SwitchRow(stringResource(R.string.irregular_heartbeat), draft.irregularHeartbeat) { v -> vm.update { it.copy(irregularHeartbeat = v) } }

            OutlinedTextField(
                value = draft.note,
                onValueChange = { v -> vm.update { it.copy(note = v) } },
                label = { Text(stringResource(R.string.note)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = draft.isValid,
                onClick = { vm.save(onSaved) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.save))
            }

            if (!draft.isValid) {
                Text(
                    "請輸入有效讀數 (60 < 收縮壓 < 260,30 < 舒張壓 < 160,且舒張壓 < 收縮壓)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onAccept: (Int) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = if (value == "0") "" else value,
        onValueChange = { v ->
            val cleaned = v.filter { it.isDigit() }.take(3)
            onAccept(cleaned.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun <T> ChipRow(title: String, items: List<Pair<T, String>>, current: T, onSelect: (T) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (value, label) ->
                FilterChip(
                    selected = current == value,
                    onClick = { onSelect(value) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ConfidenceBar(confidence: Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.confidence), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(8.dp))
            val color = when {
                confidence >= 0.85 -> MaterialTheme.colorScheme.primary
                confidence >= 0.60 -> Color(0xFFFF9500)
                else -> MaterialTheme.colorScheme.error
            }
            (1..5).forEach { i ->
                val filled = confidence * 5 >= i.toDouble() - 0.5
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (filled) color else MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.size(4.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text("${(confidence * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
