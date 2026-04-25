package com.silverbp.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.recognition.ModelLoadPhase

@Composable
fun ModelLoadBanner(phase: ModelLoadPhase, modifier: Modifier = Modifier) {
    if (phase is ModelLoadPhase.Ready) return
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (phase) {
                is ModelLoadPhase.Idle -> Text(stringResource(R.string.model_idle))
                is ModelLoadPhase.Downloading -> {
                    val pct = (phase.fraction * 100f).toInt().coerceIn(0, 100)
                    Text(stringResource(R.string.model_downloading, pct))
                    LinearProgressIndicator(
                        progress = { phase.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is ModelLoadPhase.Loading -> {
                    Text(stringResource(R.string.model_loading))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is ModelLoadPhase.Failed -> Text(
                    stringResource(R.string.model_failed, phase.message),
                    color = MaterialTheme.colorScheme.error,
                )
                ModelLoadPhase.Ready -> {} // handled above
            }
        }
    }
}
