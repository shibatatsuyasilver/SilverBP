package com.silverbp.android.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.sharing.sharePdf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(vm: ReportViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_report)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RangeChips(state.range, vm::setRange)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.report_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("區間共 ${state.readings.size} 筆讀數", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = { vm.generate { /* file ready in state */ } },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.readings.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Description, null)
                Text("  ${stringResource(R.string.generate_report)}")
            }

            state.generatedFile?.let { file ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(file.name, style = MaterialTheme.typography.bodySmall)
                        Text("大小 ${file.length() / 1024} KB", style = MaterialTheme.typography.labelSmall)
                        Button(
                            onClick = { context.sharePdf(file) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Icon(Icons.Filled.Share, null)
                            Text("  ${stringResource(R.string.share)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeChips(current: ReportRange, onSelect: (ReportRange) -> Unit) {
    val pairs = listOf(
        ReportRange.ThisMonth to "本月",
        ReportRange.LastMonth to "上月",
        ReportRange.Last30 to "30 天",
        ReportRange.Last90 to "90 天",
        ReportRange.AllTime to "全部",
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pairs.forEach { (r, label) ->
            FilterChip(
                selected = current == r,
                onClick = { onSelect(r) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}
