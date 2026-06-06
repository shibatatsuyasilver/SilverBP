package com.silverbp.android.ui.nutrition

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silverbp.android.R
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.MealType
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.ui.coach.components.GoalRing
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
    val context = LocalContext.current

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

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(stringResource(R.string.tab_nutrition), fontWeight = FontWeight.SemiBold)
            })
        },
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

                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                    Button(
                        onClick = { camera.launch(null) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, null)
                        Spacer(Modifier.size(AppSpacing.tight))
                        Text(
                            stringResource(R.string.nutrition_photo_short),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(
                        onClick = onOpenBarcode,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, null)
                        Spacer(Modifier.size(AppSpacing.tight))
                        Text(
                            stringResource(R.string.nutrition_scan_barcode),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                    OutlinedButton(
                        onClick = {
                            gallery.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, null)
                        Spacer(Modifier.size(AppSpacing.tight))
                        Text(stringResource(R.string.nutrition_pick_gallery))
                    }
                    OutlinedButton(
                        onClick = { vm.stageManual(); onOpenConfirmNew() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Edit, null)
                        Spacer(Modifier.size(AppSpacing.tight))
                        Text(stringResource(R.string.nutrition_add_manual))
                    }
                }

                Text(
                    stringResource(R.string.nutrition_recent_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.recent.isEmpty()) {
                    StandardCard {
                        Text(
                            stringResource(R.string.nutrition_today_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    StandardCard {
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
                    Button(onClick = { vm.resetCapture(); onOpenConfirmNew() }) {
                        Text(stringResource(R.string.nutrition_manual_entry))
                    }
                    Spacer(Modifier.height(AppSpacing.itemGap))
                    OutlinedButton(onClick = { vm.resetCapture() }) {
                        Text(stringResource(R.string.cancel))
                    }
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
                        d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.TAIWAN),
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
    val fmt = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.TAIWAN).withZone(ZoneId.systemDefault()) }
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
