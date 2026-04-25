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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.BpReading
import com.silverbp.android.ui.components.ModelLoadBanner
import com.silverbp.android.ui.components.chineseLabel
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(
    onCapture: () -> Unit,
    onAddManual: () -> Unit,
    vm: TodayViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelLoadBanner(phase = state.modelPhase)
            Spacer(Modifier.height(4.dp))

            val latest = state.latest
            if (latest == null) {
                EmptyTodayState(onAddManual)
            } else {
                LatestReadingCard(latest, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "共 ${state.totalCount} 筆",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCapture,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.capture_button)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

@Composable
private fun EmptyTodayState(onAddManual: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.no_readings), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.capture_cta), style = MaterialTheme.typography.bodyMedium)
            FloatingActionButton(onClick = onAddManual) {
                Icon(Icons.Filled.Edit, null)
            }
            Text(stringResource(R.string.manual_entry), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LatestReadingCard(reading: BpReading, modifier: Modifier = Modifier) {
    val cat = classify(reading.systolic, reading.diastolic)
    val color = colorFor(cat)
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.TAIWAN).withZone(zone)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.size(8.dp))
                Text(chineseLabel(cat), style = MaterialTheme.typography.labelLarge)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${reading.systolic}", style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.SemiBold))
                Text("/ ${reading.diastolic}", style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.mmhg), style = MaterialTheme.typography.bodyMedium)
            }
            reading.pulse?.let {
                Text("${stringResource(R.string.pulse)}  $it ${stringResource(R.string.bpm)}",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text(fmt.format(reading.timestamp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
