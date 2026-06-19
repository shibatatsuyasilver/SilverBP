package com.silverbp.android.ui.coach.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.silverbp.android.R
import com.silverbp.android.ui.coach.NarrationUi
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing

@Composable
fun NarrationBlock(
    state: NarrationUi,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    StandardCard(
        modifier = modifier,
        title = stringResource(R.string.coach_narration_title),
        titleTrailing = {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
        },
    ) {
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                if (state.isStreaming) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(AppSpacing.itemGap))
                }
                Text(
                    state.text.ifBlank { stringResource(R.string.coach_narration_placeholder) },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
