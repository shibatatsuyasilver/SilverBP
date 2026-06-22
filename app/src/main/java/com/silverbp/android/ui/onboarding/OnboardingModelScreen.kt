package com.silverbp.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.annotation.StringRes
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.core.NetworkInfo
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.DeviceCapabilities
import com.silverbp.android.recognition.DeviceCapabilities.RecommendedBackend
import com.silverbp.android.recognition.ModelBootstrap
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.ModelVariant
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.settings.UserSettings
import com.silverbp.android.ui.components.ExpressiveAssistChip
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.approxSizeLabel
import com.silverbp.android.ui.components.rememberCellularDownloadGate
import com.silverbp.android.ui.components.rememberModelDownloadPermissionGate
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.launch

private const val AI_STUDIO_KEY_URL = "https://aistudio.google.com/app/apikey"

private fun RecommendedBackend.toBackend(): RecognitionBackend = when (this) {
    RecommendedBackend.AICore -> RecognitionBackend.AICore
    RecommendedBackend.OnDevice -> RecognitionBackend.Local
    RecommendedBackend.Cloud -> RecognitionBackend.Cloud
}

/**
 * First-launch AI backend picker. Shown by [com.silverbp.android.ui.nav.AppNavHost]
 * after onboarding is complete but before the Google sign-in gate, whenever
 * `pickedAiBackend` is still false. Pre-selects the option recommended for this
 * phone (Pixel AICore → on-device RAM → cloud) and explains each in plain terms.
 *
 * The on-device choice only kicks off the multi-GB download when on Wi-Fi and
 * after an explicit confirm; the "type readings myself" escape hatch sets the
 * flag and proceeds without any download. [onCompleted] runs exactly once and
 * releases HOME.
 */
@Composable
fun OnboardingModelScreen(onCompleted: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val requestModelDownloadPermission = rememberModelDownloadPermissionGate()

    var recommended by remember { mutableStateOf<RecommendedBackend?>(null) }
    var selected by remember { mutableStateOf<RecognitionBackend?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }

    // On-device model choice. Pre-select the RAM-appropriate variant (E4B on
    // roomy phones, else E2B); the user can switch in the chooser below.
    val recommendedVariantId = remember { ModelCatalog.recommended(context).id }
    var selectedVariantId by remember { mutableStateOf(recommendedVariantId) }
    val chosenVariant = ModelCatalog.byId(selectedVariantId)

    // Cellular-download gate (toggle lives in Advanced Settings; off by default,
    // so on a first launch over mobile data the download simply defers to Wi-Fi).
    val settings by ServiceLocator.userSettings.flow
        .collectAsStateWithLifecycle(initialValue = UserSettings())
    val cellularGate = rememberCellularDownloadGate(settings.allowDownloadOverCellular)

    // Compute the recommendation once (AICore availability is a suspend probe).
    LaunchedEffect(Unit) {
        val rec = DeviceCapabilities.recommendBackend(context)
        recommended = rec
        selected = rec.toBackend()
    }

    fun applyAndFinish(backend: RecognitionBackend, download: Boolean, allowMetered: Boolean = false) {
        scope.launch {
            val s = ServiceLocator.userSettings
            when (backend) {
                RecognitionBackend.Local -> {
                    s.setRecognitionBackend(RecognitionBackend.Local)
                    s.setSelectedModelId(chosenVariant.id)
                }
                RecognitionBackend.Cloud -> {
                    s.setGeminiApiKey(apiKey.trim())
                    s.setRecognitionBackend(RecognitionBackend.Cloud)
                }
                RecognitionBackend.AICore -> {
                    s.setRecognitionBackend(RecognitionBackend.AICore)
                }
            }
            s.setPickedAiBackend(true)
            // Kick off any background model work AFTER the flag is set so the
            // HOME ModelLoadBanner reflects progress. These return immediately.
            if (backend == RecognitionBackend.Local && download) {
                ModelBootstrap.downloadAndPreload(context, chosenVariant, allowMetered = allowMetered)
            } else if (backend == RecognitionBackend.AICore) {
                ModelBootstrap.preloadAICore(context)
            }
            onCompleted()
        }
    }

    fun onContinue() {
        when (selected) {
            RecognitionBackend.Local ->
                if (!NetworkInfo.isMetered(context)) {
                    showDownloadDialog = true
                } else {
                    // On mobile data: confirm when the user has allowed cellular
                    // downloads, otherwise show the Wi-Fi-only notice and finish
                    // without downloading (it'll be ready in Settings on Wi-Fi).
                    cellularGate.request(
                        approxSizeLabel(chosenVariant.approxSizeGB),
                        onProceed = { allowMetered ->
                            requestModelDownloadPermission {
                                applyAndFinish(
                                    RecognitionBackend.Local,
                                    download = true,
                                    allowMetered = allowMetered,
                                )
                            }
                        },
                        onBlocked = { applyAndFinish(RecognitionBackend.Local, download = false) },
                    )
                }
            RecognitionBackend.Cloud ->
                if (apiKey.isBlank()) keyError = true
                else applyAndFinish(RecognitionBackend.Cloud, download = false)
            RecognitionBackend.AICore -> applyAndFinish(RecognitionBackend.AICore, download = false)
            null -> Unit
        }
    }

    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text(stringResource(R.string.onboarding_model_download_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.onboarding_model_download_body,
                        approxSizeLabel(chosenVariant.approxSizeGB),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadDialog = false
                    requestModelDownloadPermission {
                        applyAndFinish(RecognitionBackend.Local, download = true)
                    }
                }) { Text(stringResource(R.string.onboarding_model_download_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDownloadDialog = false
                    applyAndFinish(RecognitionBackend.Local, download = false)
                }) { Text(stringResource(R.string.onboarding_model_download_later)) }
            },
        )
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV * 2),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            OnboardingHeroIcon(icon = Icons.Filled.AutoAwesome)
            Text(
                stringResource(R.string.onboarding_model_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.onboarding_model_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selected == null) {
                Spacer(Modifier.size(AppSpacing.screenV))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            // On-device
            OptionCard(
                title = stringResource(R.string.onboarding_model_local_title),
                desc = stringResource(R.string.onboarding_model_local_desc),
                selected = selected == RecognitionBackend.Local,
                recommended = recommended == RecommendedBackend.OnDevice,
                onSelect = { selected = RecognitionBackend.Local },
            )

            // Cloud
            OptionCard(
                title = stringResource(R.string.onboarding_model_cloud_title),
                desc = stringResource(R.string.onboarding_model_cloud_desc),
                selected = selected == RecognitionBackend.Cloud,
                recommended = recommended == RecommendedBackend.Cloud,
                onSelect = { selected = RecognitionBackend.Cloud },
            ) {
                if (selected == RecognitionBackend.Cloud) {
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.onboarding_model_cloud_get_key),
                        onClick = { uriHandler.openUri(AI_STUDIO_KEY_URL) },
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; keyError = false },
                        label = { Text(stringResource(R.string.onboarding_model_cloud_key_label)) },
                        isError = keyError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            if (keyError) R.string.onboarding_model_cloud_key_error
                            else R.string.onboarding_model_cloud_key_help
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (keyError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // AICore — only when usable (recommended exactly when available).
            if (recommended == RecommendedBackend.AICore) {
                OptionCard(
                    title = stringResource(R.string.onboarding_model_aicore_title),
                    desc = stringResource(R.string.onboarding_model_aicore_desc),
                    selected = selected == RecognitionBackend.AICore,
                    recommended = true,
                    onSelect = { selected = RecognitionBackend.AICore },
                )
            }

            // On-device model chooser — its own card, shown only when the
            // on-device backend is selected. Pre-selects the RAM-appropriate
            // variant; the user can switch.
            if (selected == RecognitionBackend.Local) {
                StandardCard(title = stringResource(R.string.onboarding_model_choose_variant)) {
                    ModelCatalog.variants.forEachIndexed { index, variant ->
                        if (index > 0) HorizontalDivider()
                        VariantChooserRow(
                            variant = variant,
                            selected = selectedVariantId == variant.id,
                            recommended = variant.id == recommendedVariantId,
                            onSelect = { selectedVariantId = variant.id },
                        )
                    }
                    if (NetworkInfo.isMetered(context) && !settings.allowDownloadOverCellular) {
                        Spacer(Modifier.size(AppSpacing.tight))
                        Text(
                            stringResource(R.string.onboarding_model_local_wifi_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.size(AppSpacing.itemGap))
            ExpressivePrimaryButton(
                text = stringResource(R.string.onboarding_model_confirm),
                onClick = { onContinue() },
                enabled = true,
                fillWidth = true,
            )
            TextButton(
                onClick = { applyAndFinish(RecognitionBackend.Local, download = false) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.onboarding_model_skip),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun OptionCard(
    title: String,
    desc: String,
    selected: Boolean,
    recommended: Boolean,
    onSelect: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    // Selectable StandardCard: the whole card is tappable (onClick), and the
    // chosen backend reads at a glance via a brand-tinted fill + the leading
    // RadioButton — mirrors the AI-backend chooser pattern. surfaceContainer
    // fill when idle keeps the unselected options calm for older eyes.
    StandardCard(
        onClick = onSelect,
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.outline,
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.tight)) {
                if (recommended) {
                    ExpressiveAssistChip(
                        label = stringResource(R.string.onboarding_model_recommended),
                        onClick = onSelect,
                    )
                }
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                extra()
            }
        }
    }
}

/**
 * A selectable on-device model row in the onboarding picker: a leading
 * RadioButton, the variant name + approximate download size, a "recommended"
 * chip on the RAM-appropriate one, and the variant's one-line notes. Lighter
 * than the Settings [com.silverbp.android.ui.settings.AdvancedSettingsScreen]
 * row — no download/delete actions, just the choice.
 */
@Composable
private fun VariantChooserRow(
    variant: ModelVariant,
    selected: Boolean,
    recommended: Boolean,
    onSelect: () -> Unit,
) {
    // Strip the catalog's parenthetical qualifier ("Gemma 4 E2B (Recommended)"
    // → "Gemma 4 E2B"); onboarding shows its own RAM-based recommendation badge.
    val name = stringResource(variant.displayNameRes).substringBefore(" (")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = AppSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
        ) {
            // Name (takes remaining width) + size pinned to the end on one line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(AppSpacing.tight))
                Text(
                    approxSizeLabel(variant.approxSizeGB),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (recommended) {
                Text(
                    "★ " + stringResource(R.string.onboarding_model_recommended),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(onboardingBlurbRes(variant.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Plain-language one-liner per variant for the onboarding chooser. */
@StringRes
private fun onboardingBlurbRes(variantId: String): Int = when (variantId) {
    "gemma-4-E2B-it" -> R.string.onboarding_model_blurb_e2b
    "gemma-4-E4B-it" -> R.string.onboarding_model_blurb_e4b
    else -> R.string.onboarding_model_blurb_other
}

/**
 * Tinted brand "hero" icon tile heading an onboarding step — a rounded
 * primary-tinted square with a centred icon, mirroring the empty-state tiles in
 * the Today card family. Pure styling; no state.
 */
@Composable
private fun OnboardingHeroIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
