package com.silverbp.android.ui.coach.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.ui.coach.ModuleKey
import com.silverbp.android.ui.coach.ModuleRowUi
import androidx.compose.ui.res.stringResource

@Composable
fun ModuleCard(
    row: ModuleRowUi,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    val displayLabel = row.displayName.ifBlank { stringResource(row.moduleKey.labelRes()) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onTap != null) it.clickable { onTap() } else it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoalRing(ratio = row.ratio)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.coach_module_progress, row.completed, row.target),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun ModuleKey.labelRes(): Int = when (this) {
    ModuleKey.Exercise -> R.string.coach_module_exercise
    ModuleKey.Diet -> R.string.coach_module_diet
    ModuleKey.Sleep -> R.string.coach_module_sleep
    ModuleKey.Medication -> R.string.coach_module_medication
}
