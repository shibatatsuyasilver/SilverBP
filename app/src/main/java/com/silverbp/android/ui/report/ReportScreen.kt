package com.silverbp.android.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.sharing.sharePdf
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressiveFilterChip
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.paywall.GateReason
import com.silverbp.android.ui.paywall.LocalPaywallController
import com.silverbp.android.ui.theme.AppSpacing

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
            AppTopBar(
                title = stringResource(R.string.report_screen_title),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            RangeChips(state.range, vm::setRange)

            StandardCard(
                title = stringResource(R.string.report_title),
            ) {
                Text(
                    stringResource(R.string.report_range_count, state.readings.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Premium tiering note (Phase 3): the free summary PDF is never
            // blocked — this only tells the free user the per-reading table is a
            // Premium add-on and offers an upgrade. Hidden when premium (which is
            // always the case while PREMIUM_ENFORCED=false → zero behaviour change).
            if (!state.isPremium) {
                StandardCard(
                    title = stringResource(R.string.gate_pdf_detail_title),
                ) {
                    Text(
                        stringResource(R.string.gate_pdf_detail_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { paywall.show(GateReason.PdfDetail) }) {
                        Text(stringResource(R.string.gate_upgrade_cta))
                    }
                }
            }

            if (state.isGenerating) {
                ExpressivePrimaryButton(
                    text = "${stringResource(R.string.generate_report)}…",
                    onClick = {},
                    enabled = false,
                    fillWidth = true,
                )
            } else {
                ExpressivePrimaryButton(
                    text = stringResource(R.string.generate_report),
                    onClick = { vm.generate { /* file ready in state */ } },
                    icon = Icons.Filled.Description,
                    enabled = state.readings.isNotEmpty(),
                    fillWidth = true,
                )
            }

            state.errorMessage?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.generatedFile?.let { file ->
                StandardCard {
                    Text(file.name, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.report_file_size_kb, file.length() / 1024),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.share),
                        onClick = { context.sharePdf(file) },
                        icon = Icons.Filled.Share,
                        fillWidth = true,
                    )
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        pairs.forEach { (r, label) ->
            ExpressiveFilterChip(
                label = label,
                selected = current == r,
                onClick = { onSelect(r) },
            )
        }
    }
}
