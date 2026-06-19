package com.silverbp.android.ui.nutrition

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.MealType
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.settings.BarcodeRegion
import com.silverbp.android.ui.coach.components.GoalRing
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.ModelLoadBanner
import com.silverbp.android.ui.components.rememberModelDownloadPermissionGate
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen(
    onOpenConfirmNew: () -> Unit,
    onOpenConfirmEdit: (String) -> Unit,
    onOpenBarcode: () -> Unit,
    vm: NutritionViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val capture by vm.capturePhase.collectAsStateWithLifecycle()
    val readiness by vm.readiness.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val requestModelDownloadPermission = rememberModelDownloadPermissionGate()
    // Barcode lookup (Open Food Facts) only pays off in well-covered regions; hide
    // it elsewhere so Taiwan users aren't misled into scanning 超商 hot food.
    val barcodeSupported = remember { BarcodeRegion.isBarcodeSupported(context) }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) vm.analyzeUri(uri, onOpenConfirmNew)
    }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) vm.analyzeBitmap(bmp, onOpenConfirmNew)
    }
    // 相機需要 CAMERA 權限,未授權就啟動 TakePicturePreview 會擲出 SecurityException。
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            camera.launch(null)
        } else {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.camera_permission_denied)) }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = stringResource(R.string.tab_nutrition))
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
            ) {
                DailySummaryCard(state)
                SodiumTrendCard(state)

                // On-device model not yet ready: show download/loading progress and
                // disable recognition until the model is fully downloaded and loaded.
                if (readiness.showModelBanner) {
                    ModelLoadBanner(phase = readiness.phase)
                    if (readiness.phase is ModelLoadPhase.Idle ||
                        readiness.phase is ModelLoadPhase.Failed
                    ) {
                        ExpressivePrimaryButton(
                            text = stringResource(R.string.nutrition_download_model_cta),
                            onClick = {
                                requestModelDownloadPermission { vm.downloadModel() }
                            },
                            fillWidth = true,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                    ExpressivePrimaryButton(
                        text = stringResource(R.string.nutrition_photo_short),
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                camera.launch(null)
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                        icon = Icons.Filled.PhotoCamera,
                        enabled = readiness.ready,
                        modifier = Modifier.weight(1f),
                    )
                    if (barcodeSupported) {
                        ExpressiveSecondaryButton(
                            text = stringResource(R.string.nutrition_scan_barcode),
                            onClick = onOpenBarcode,
                            icon = Icons.Filled.QrCodeScanner,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.nutrition_pick_gallery),
                        onClick = {
                            gallery.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        icon = Icons.Filled.PhotoLibrary,
                        enabled = readiness.ready,
                        modifier = Modifier.weight(1f),
                    )
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.nutrition_add_manual),
                        onClick = { vm.stageManual(); onOpenConfirmNew() },
                        icon = Icons.Filled.Edit,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (state.recent.isEmpty()) {
                    StandardCard(title = stringResource(R.string.nutrition_recent_title)) {
                        Text(
                            stringResource(R.string.nutrition_today_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    StandardCard(title = stringResource(R.string.nutrition_recent_title)) {
                        state.recent.take(20).forEachIndexed { idx, log ->
                            MealRow(log) { onOpenConfirmEdit(log.id.toString()) }
                            if (idx < state.recent.take(20).size - 1) Spacer(Modifier.height(AppSpacing.tight))
                        }
                    }
                }
                Spacer(Modifier.height(AppSpacing.itemGap))
            }

            when (val c = capture) {
                NutritionCapturePhase.Analyzing -> CaptureOverlay {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(AppSpacing.itemGap))
                    Text(stringResource(R.string.nutrition_analyzing), color = Color.White)
                }
                is NutritionCapturePhase.Error -> CaptureOverlay {
                    Text(
                        stringResource(R.string.nutrition_analyze_failed),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(AppSpacing.sectionGap))
                    ExpressivePrimaryButton(
                        text = stringResource(R.string.nutrition_manual_entry),
                        onClick = { vm.resetCapture(); onOpenConfirmNew() },
                    )
                    Spacer(Modifier.height(AppSpacing.itemGap))
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = { vm.resetCapture() },
                    )
                }
                NutritionCapturePhase.NeedsModel -> CaptureOverlay {
                    val modelPhase by ServiceLocator.modelLoadStatus.phase
                        .collectAsStateWithLifecycle()
                    Text(
                        stringResource(R.string.nutrition_needs_model_title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(AppSpacing.tight))
                    Text(
                        stringResource(R.string.nutrition_needs_model_body),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(AppSpacing.sectionGap))
                    // Progress while the model downloads / loads (reuses the banner).
                    ModelLoadBanner(phase = modelPhase)
                    when (modelPhase) {
                        ModelLoadPhase.Ready -> {
                            Text(
                                stringResource(R.string.nutrition_model_ready_reselect),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(AppSpacing.itemGap))
                            ExpressivePrimaryButton(
                                text = stringResource(R.string.nutrition_done),
                                onClick = { vm.resetCapture() },
                            )
                        }
                        is ModelLoadPhase.Downloading, ModelLoadPhase.Loading -> Unit
                        else -> {
                            ExpressivePrimaryButton(
                                text = stringResource(R.string.nutrition_download_model_cta),
                                onClick = {
                                    requestModelDownloadPermission { vm.downloadModel() }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(AppSpacing.itemGap))
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.nutrition_manual_entry),
                        onClick = { vm.resetCapture(); onOpenConfirmNew() },
                    )
                    Spacer(Modifier.height(AppSpacing.tight))
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = { vm.resetCapture() },
                    )
                }
                NutritionCapturePhase.ModelLoading -> CaptureOverlay {
                    val modelPhase by ServiceLocator.modelLoadStatus.phase
                        .collectAsStateWithLifecycle()
                    Text(
                        stringResource(R.string.nutrition_model_loading_title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(AppSpacing.tight))
                    Text(
                        stringResource(R.string.nutrition_model_loading_body),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(AppSpacing.sectionGap))
                    ModelLoadBanner(phase = modelPhase)
                    if (modelPhase is ModelLoadPhase.Ready) {
                        Text(
                            stringResource(R.string.nutrition_model_ready_reselect),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(AppSpacing.itemGap))
                        ExpressivePrimaryButton(
                            text = stringResource(R.string.nutrition_done),
                            onClick = { vm.resetCapture() },
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.itemGap))
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.nutrition_manual_entry),
                        onClick = { vm.resetCapture(); onOpenConfirmNew() },
                    )
                    Spacer(Modifier.height(AppSpacing.tight))
                    ExpressiveSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = { vm.resetCapture() },
                    )
                }
                NutritionCapturePhase.Idle -> Unit
            }
        }
    }
}

@Composable
private fun CaptureOverlay(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun DailySummaryCard(state: NutritionUiState) {
    StandardCard(title = stringResource(R.string.nutrition_today_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoalRing(
                ratio = if (state.sodiumTargetMg > 0) (state.todaySodiumMg / state.sodiumTargetMg).toFloat() else 0f,
                sizeDp = 72,
            )
            Spacer(Modifier.width(AppSpacing.sectionGap))
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.tight)) {
                Text(
                    stringResource(
                        R.string.nutrition_sodium_summary,
                        state.todaySodiumMg.toInt(),
                        state.sodiumTargetMg,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.nutrition_estimate_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.nutrition_calories_summary, state.todayCalories.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.nutrition_macros_summary,
                        state.todayProteinG.toInt(),
                        state.todayCarbsG.toInt(),
                        state.todayFatG.toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SodiumTrendCard(state: NutritionUiState) {
    val days = state.last7DaysSodium
    if (days.isEmpty()) return
    // Follow the in-app language (per-app locale), not the device locale —
    // Locale.getDefault() returns the device locale here and would render the
    // weekday labels in Chinese even when English is selected.
    val locale = LocalConfiguration.current.locales[0]
    val scaleMax = maxOf(state.sodiumTargetMg.toDouble(), days.maxOf { it.sodiumMg }).coerceAtLeast(1.0)
    StandardCard(title = stringResource(R.string.nutrition_trend_title)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(96.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.tight),
        ) {
            days.forEach { d ->
                val frac = (d.sodiumMg / scaleMax).coerceIn(0.0, 1.0)
                val barColor = if (d.sodiumMg > state.sodiumTargetMg) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
                ) {
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height((4 + (64 * frac)).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(barColor),
                    )
                    Text(
                        d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MealRow(log: FoodLog, onClick: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) {
        DateTimeFormatter.ofPattern("MM/dd HH:mm", locale).withZone(ZoneId.systemDefault())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = AppSpacing.itemGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        log.photoFilename?.let { name ->
            AsyncImage(
                model = File(File(context.filesDir, "photos"), name),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                log.description.ifBlank { stringResource(mealTypeLabel(log.mealType)) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                fmt.format(log.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            log.calories?.let {
                Text(
                    stringResource(R.string.nutrition_kcal_short, it.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(sodiumLevelLabel(log.sodiumLevel)),
                style = MaterialTheme.typography.labelSmall,
                color = sodiumLevelColor(log.sodiumLevel),
            )
        }
    }
}

@Composable
private fun sodiumLevelColor(level: SodiumLevel): Color = when (level) {
    SodiumLevel.Low -> MaterialTheme.colorScheme.primary
    SodiumLevel.Mid -> MaterialTheme.colorScheme.onSurfaceVariant
    SodiumLevel.High -> MaterialTheme.colorScheme.error
}

private fun mealTypeLabel(mt: MealType): Int = when (mt) {
    MealType.Breakfast -> R.string.nutrition_meal_breakfast
    MealType.Lunch -> R.string.nutrition_meal_lunch
    MealType.Dinner -> R.string.nutrition_meal_dinner
    MealType.Snack -> R.string.nutrition_meal_snack
}

private fun sodiumLevelLabel(lvl: SodiumLevel): Int = when (lvl) {
    SodiumLevel.Low -> R.string.nutrition_sodium_low
    SodiumLevel.Mid -> R.string.nutrition_sodium_mid
    SodiumLevel.High -> R.string.nutrition_sodium_high
}
