package com.silverbp.android.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silverbp.android.R
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.MealType
import com.silverbp.android.nutrition.Portion
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.recognition.ExtractedFoodItem
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import java.io.File
import kotlin.math.roundToInt

/** Amber caution colour for the "sodium hard to estimate" note. */
private val SodiumCaution = Color(0xFFEF6C00)

@Composable
fun NutritionConfirmScreen(
    idArg: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: NutritionConfirmViewModel = viewModel(),
) {
    LaunchedEffect(idArg) { vm.init(idArg) }
    val meal by vm.meal.collectAsStateWithLifecycle()
    val m = meal
    if (m != null) {
        RecognizedMealContent(meal = m, onSaved = onSaved, onCancel = onCancel, vm = vm)
    } else {
        FlatConfirmContent(idArg = idArg, onSaved = onSaved, onCancel = onCancel, vm = vm)
    }
}

// ==========================================================================
// Recognized mode — photo → per-item portion picker → DB-computed nutrition
// ==========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecognizedMealContent(
    meal: RecognizedMeal,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: NutritionConfirmViewModel,
) {
    val context = LocalContext.current
    // Per-item edit state: grams override (matched items) + manual kcal (unmatched).
    val grams = remember { mutableStateMapOf<Int, Double>() }
    val manualKcal = remember { mutableStateMapOf<Int, Double>() }

    // Resolve every item live; recomputes as edits change. Never drops items.
    val resolved = meal.items.mapIndexed { idx, ex ->
        resolveFoodItem(ex, grams[idx], manualKcal[idx])
    }
    val kcal = resolved.sumOf { it.kcal ?: 0.0 }
    val sodLo = resolved.sumOf { it.sodiumLowMg ?: 0.0 }
    val sodHi = resolved.sumOf { it.sodiumHighMg ?: 0.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.nutrition_confirm_new_title), fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                },
                actions = {
                    TextButton(
                        enabled = meal.items.isNotEmpty(),
                        onClick = { vm.saveRecognizedMeal(meal, resolved, onSaved) },
                    ) {
                        Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            meal.photoFilename?.let { name ->
                AsyncImage(
                    model = File(File(context.filesDir, "photos"), name),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(AppSpacing.cardCorner)),
                )
            }

            Text(
                stringResource(R.string.nutrition_reference_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            meal.overallConfidence?.let { c ->
                Text(
                    stringResource(R.string.nutrition_overall_confidence, (c * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            meal.items.forEachIndexed { idx, item ->
                RecognizedItemCard(
                    item = item,
                    resolved = resolved[idx],
                    onSetGrams = { g -> if (g == null) grams.remove(idx) else grams[idx] = g },
                    onSetPortion = { p -> resolved[idx].defaultPortionGrams?.let { grams[idx] = p.grams(it) } },
                    onManualKcal = { k -> if (k == null) manualKcal.remove(idx) else manualKcal[idx] = k },
                )
            }

            // Meal totals
            StandardCard(title = stringResource(R.string.nutrition_meal_totals)) {
                Text(
                    stringResource(R.string.nutrition_kcal_short, kcal.roundToInt()),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.nutrition_sodium_approx_range, sodLo.roundToInt(), sodHi.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                stringResource(R.string.nutrition_recognized_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.nutrition_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecognizedItemCard(
    item: ExtractedFoodItem,
    resolved: ResolvedFoodItem,
    onSetGrams: (Double?) -> Unit,
    onSetPortion: (Portion) -> Unit,
    onManualKcal: (Double?) -> Unit,
) {
    StandardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            item.nameEn?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "  $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.confidence?.let {
                Text(
                    "  ${(it * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        when (resolved.state) {
            ItemState.Matched, ItemState.Approx -> {
                if (resolved.state == ItemState.Approx) {
                    Text(
                        stringResource(R.string.nutrition_approx_match),
                        style = MaterialTheme.typography.labelSmall,
                        color = SodiumCaution,
                    )
                }
                val def = resolved.defaultPortionGrams ?: 100.0
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    Portion.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = kotlin.math.abs(resolved.grams - p.grams(def)) < 0.5,
                            onClick = { onSetPortion(p) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = Portion.entries.size),
                        ) {
                            Text(portionLabel(p, def))
                        }
                    }
                }
                SyncedNumberField(
                    label = stringResource(R.string.nutrition_grams_label),
                    value = resolved.grams,
                    onChange = onSetGrams,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)) {
                    Macro(stringResource(R.string.nutrition_macro_kcal), stringResource(R.string.nutrition_kcal_short, (resolved.kcal ?: 0.0).roundToInt()))
                    Macro(stringResource(R.string.nutrition_macro_protein), "${(resolved.proteinG ?: 0.0).roundToInt()} g")
                    Macro(stringResource(R.string.nutrition_macro_fat), "${(resolved.fatG ?: 0.0).roundToInt()} g")
                    Macro(stringResource(R.string.nutrition_macro_carb), "${(resolved.carbG ?: 0.0).roundToInt()} g")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.tight),
                ) {
                    Text(
                        stringResource(R.string.nutrition_sodium_approx_range, (resolved.sodiumLowMg ?: 0.0).roundToInt(), (resolved.sodiumHighMg ?: 0.0).roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (resolved.highSodiumUncertainty) {
                        Text(
                            stringResource(R.string.nutrition_sodium_uncertain_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = SodiumCaution,
                        )
                    }
                }
            }
            ItemState.Manual, ItemState.Unknown -> {
                Text(
                    stringResource(R.string.nutrition_enter_kcal_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SyncedNumberField(
                    label = stringResource(R.string.nutrition_manual_kcal_label),
                    value = resolved.kcal,
                    onChange = onManualKcal,
                )
            }
        }
    }
}

/**
 * Number field whose text resyncs when [value] changes from the outside (e.g. an
 * S/M/L preset or an online lookup populating it), while preserving in-progress
 * typing. Used for the gram override and manual-calorie inputs.
 */
@Composable
private fun SyncedNumberField(label: String, value: Double?, onChange: (Double?) -> Unit) {
    var text by remember { mutableStateOf(value?.let { formatNum(it) } ?: "") }
    LaunchedEffect(value) {
        if (text.trim().toDoubleOrNull() != value) text = value?.let { formatNum(it) } ?: ""
    }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            val clean = s.trim()
            onChange(if (clean.isEmpty()) null else clean.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Macro(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.tight)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun portionLabel(p: Portion, defaultGrams: Double): String {
    val g = p.grams(defaultGrams).roundToInt()
    return when (p) {
        Portion.Small -> stringResource(R.string.nutrition_portion_small, g)
        Portion.Medium -> stringResource(R.string.nutrition_portion_mid, g)
        Portion.Large -> stringResource(R.string.nutrition_portion_large, g)
    }
}

// ==========================================================================
// Flat mode — barcode / manual / edit existing
// ==========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlatConfirmContent(
    idArg: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: NutritionConfirmViewModel,
) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val isEditing = idArg != null
    var showDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.nutrition_confirm_edit_title
                            else R.string.nutrition_confirm_new_title
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(
                        enabled = draft != null,
                        onClick = { vm.save(onSaved) },
                    ) {
                        Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        val d = draft
        if (d == null) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            d.photoFilename?.let { name ->
                AsyncImage(
                    model = File(File(context.filesDir, "photos"), name),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(AppSpacing.cardCorner)),
                )
            }

            OutlinedTextField(
                value = d.description,
                onValueChange = { v -> vm.update { it.copy(description = v) } },
                label = { Text(stringResource(R.string.nutrition_field_description)) },
                placeholder = { Text(stringResource(R.string.nutrition_field_description_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            StandardCard(title = stringResource(R.string.nutrition_meal_label)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                    MealType.entries.forEach { mt ->
                        FilterChip(
                            selected = d.mealType == mt,
                            onClick = { vm.update { it.copy(mealType = mt) } },
                            label = { Text(stringResource(mealTypeLabel(mt))) },
                        )
                    }
                }
            }

            StandardCard(title = stringResource(R.string.nutrition_sodium_section)) {
                Text(
                    stringResource(R.string.nutrition_sodium_level),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                    SodiumLevel.entries.forEach { lvl ->
                        FilterChip(
                            selected = d.sodiumLevel == lvl,
                            onClick = { vm.update { it.copy(sodiumLevel = lvl) } },
                            label = { Text(stringResource(sodiumLevelLabel(lvl))) },
                        )
                    }
                }
                if (d.sodiumMgLow != null && d.sodiumMgHigh != null) {
                    Text(
                        stringResource(
                            R.string.nutrition_sodium_range,
                            d.sodiumMgLow!!.toInt(),
                            d.sodiumMgHigh!!.toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NumberFieldD(
                    label = stringResource(R.string.nutrition_sodium_mg_label),
                    value = d.sodiumMg,
                    onChange = { v -> vm.update { it.copy(sodiumMg = v) } },
                )
                Text(
                    stringResource(R.string.nutrition_sodium_photo_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            StandardCard(title = stringResource(R.string.nutrition_macros_section)) {
                NumberFieldD(stringResource(R.string.nutrition_calories_label), d.calories) { v -> vm.update { it.copy(calories = v) } }
                HorizontalDivider()
                NumberFieldD(stringResource(R.string.nutrition_protein_label), d.proteinG) { v -> vm.update { it.copy(proteinG = v) } }
                HorizontalDivider()
                NumberFieldD(stringResource(R.string.nutrition_carbs_label), d.carbsG) { v -> vm.update { it.copy(carbsG = v) } }
                HorizontalDivider()
                NumberFieldD(stringResource(R.string.nutrition_fat_label), d.fatG) { v -> vm.update { it.copy(fatG = v) } }
                HorizontalDivider()
                NumberFieldD(stringResource(R.string.nutrition_sugar_label), d.sugarG) { v -> vm.update { it.copy(sugarG = v) } }
                HorizontalDivider()
                NumberFieldD(stringResource(R.string.nutrition_fiber_label), d.fiberG) { v -> vm.update { it.copy(fiberG = v) } }
            }

            OutlinedTextField(
                value = d.note,
                onValueChange = { v -> vm.update { it.copy(note = v) } },
                label = { Text(stringResource(R.string.nutrition_note_label)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.nutrition_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.nutrition_delete_title)) },
            text = { Text(stringResource(R.string.nutrition_delete_message)) },
            confirmButton = {
                TextButton(onClick = { showDelete = false; vm.delete(onSaved) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun NumberFieldD(label: String, value: Double?, onChange: (Double?) -> Unit) {
    // Local text state is authoritative while editing so intermediate input
    // (e.g. "1." mid-decimal) isn't reformatted out from under the cursor.
    var text by remember { mutableStateOf(value?.let { formatNum(it) } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            val clean = s.trim()
            onChange(if (clean.isEmpty()) null else clean.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

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
