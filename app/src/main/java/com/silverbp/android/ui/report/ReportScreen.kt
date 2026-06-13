package com.silverbp.android.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.silverbp.android.ui.paywall.GateReason
import com.silverbp.android.ui.paywall.LocalPaywallController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onClose: () -> Unit = {},
    vm: ReportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    // Premium gate (Phase 3): the app-wide hoisted paywall (PaywallHost). The
    // inline PDF upsell card's CTA opens it with the PdfDetail reason.
    val paywall = LocalPaywallController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        }
    ) { padding ->
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
                    Text(stringResource(R.string.report_range_count, state.readings.size), style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Premium tiering note (Phase 3): the free summary PDF is never
            // blocked — this only tells the free user the per-reading table is a
            // Premium add-on and offers an upgrade. Hidden when premium (which is
            // always the case while PREMIUM_ENFORCED=false → zero behaviour change).
            if (!state.isPremium) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.gate_pdf_detail_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.gate_pdf_detail_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(onClick = { paywall.show(GateReason.PdfDetail) }) {
                            Text(stringResource(R.string.gate_upgrade_cta))
                        }
                    }
                }
            }

            Button(
                onClick = { vm.generate { /* file ready in state */ } },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.readings.isNotEmpty() && !state.isGenerating,
            ) {
                if (state.isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("  ${stringResource(R.string.generate_report)}…")
                } else {
                    Icon(Icons.Filled.Description, null)
                    Text("  ${stringResource(R.string.generate_report)}")
                }
            }

            state.errorMessage?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.generatedFile?.let { file ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(file.name, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.report_file_size_kb, file.length() / 1024), style = MaterialTheme.typography.labelSmall)
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
        ReportRange.ThisMonth to stringResource(R.string.range_this_month),
        ReportRange.LastMonth to stringResource(R.string.range_last_month),
        ReportRange.Last30 to stringResource(R.string.range_30d),
        ReportRange.Last90 to stringResource(R.string.range_90d),
        ReportRange.AllTime to stringResource(R.string.range_all),
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
