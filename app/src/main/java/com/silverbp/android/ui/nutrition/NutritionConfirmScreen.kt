package com.silverbp.android.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.silverbp.android.R
import com.silverbp.android.nutrition.MealType
import com.silverbp.android.nutrition.NutrimentBasis
import com.silverbp.android.nutrition.NutritionDatabase
import com.silverbp.android.nutrition.Portion
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.nutrition.compute
import com.silverbp.android.recognition.ExtractedFoodItem
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressiveFilterChip
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.SegmentedControl
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import java.io.File
import kotlin.math.roundToInt

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
    val isSaving by vm.isSaving.collectAsStateWithLifecycle()
    val portions = remember { mutableStateMapOf<Int, Portion>() }
    // idx -> excluded? Lets the user drop a wrong/duplicate recognition (e.g. a
    // phantom second rice or a misidentified side) so it stops inflating the meal
    // total. All items start included; the trash icon on a card toggles it.
    val excluded = remember(meal) { mutableStateMapOf<Int, Boolean>() }
    fun isExcluded(idx: Int): Boolean = excluded[idx] == true
    fun portionFor(idx: Int, item: ExtractedFoodItem): Portion =
        portions[idx] ?: Portion.fromHint(item.portionHint)

    // Live totals (recomposes as portions/exclusions change). Totals sum only
    // matched items; [included] counts every non-excluded item (matched or not)
    // so Save stays enabled even when nothing matched — that path logs a
    // description-only fallback rather than appearing to save nothing.
    var kcal = 0.0; var sodLo = 0.0; var sodHi = 0.0; var included = 0
    meal.items.forEachIndexed { idx, ex ->
        if (isExcluded(idx)) return@forEachIndexed
        included++
        val rec = NutritionDatabase.match(ex.name, ex.nameEn) ?: return@forEachIndexed
        val c = rec.compute(portionFor(idx, ex))
        kcal += c.kcal; sodLo += c.sodiumLowMg; sodHi += c.sodiumHighMg
    }

    fun saveMeal() {
        vm.saveRecognizedMeal(
            meal,
            portions.toMap(),
            excluded.filterValues { it }.keys,
            onSaved,
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.nutrition_confirm_new_title),
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                },
                actions = {
                    TextButton(
                        enabled = included > 0 && !isSaving,
                        onClick = { saveMeal() },
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
                    portion = portionFor(idx, item),
                    onPortion = { portions[idx] = it },
                    excluded = isExcluded(idx),
                    onToggleExcluded = { excluded[idx] = !isExcluded(idx) },
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

            // Prominent primary save — the Expressive CTA mirrors the TopAppBar
            // save action (same saveMeal call), giving a big, obvious confirmation
            // target at the end of the form.
            ExpressivePrimaryButton(
                text = stringResource(R.string.save),
                onClick = { saveMeal() },
                enabled = included > 0 && !isSaving,
                icon = Icons.Filled.Check,
                fillWidth = true,
            )

            Spacer(Modifier.size(AppSpacing.itemGap))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecognizedItemCard(
    item: ExtractedFoodItem,
    portion: Portion,
    onPortion: (Portion) -> Unit,
    excluded: Boolean,
    onToggleExcluded: () -> Unit,
) {
    val rec = remember(item) { NutritionDatabase.match(item.name, item.nameEn) }
    StandardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (excluded) TextDecoration.LineThrough else null,
            )
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
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleExcluded) {
                Icon(
                    if (excluded) Icons.Filled.Add else Icons.Filled.Delete,
                    contentDescription = stringResource(
                        if (excluded) R.string.nutrition_item_include
                        else R.string.nutrition_item_exclude
                    ),
                    tint = if (excluded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
        }

        // Excluded items collapse to just the struck-through name + restore icon
        // and contribute nothing to the meal totals.
        if (excluded) return@StandardCard

        if (rec != null) {
            SegmentedControl(
                options = Portion.entries.map { portionLabel(it, rec.defaultPortionGrams) },
                selectedIndex = Portion.entries.indexOf(portion).coerceAtLeast(0),
                onSelect = { i -> onPortion(Portion.entries[i]) },
            )
            val c = rec.compute(portion)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)) {
                Macro(stringResource(R.string.nutrition_macro_kcal), stringResource(R.string.nutrition_kcal_short, c.kcal.roundToInt()))
                Macro(stringResource(R.string.nutrition_macro_protein), "${c.proteinG.roundToInt()} g")
                Macro(stringResource(R.string.nutrition_macro_fat), "${c.fatG.roundToInt()} g")
                Macro(stringResource(R.string.nutrition_macro_carb), "${c.carbG.roundToInt()} g")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.tight),
            ) {
                Text(
                    stringResource(R.string.nutrition_sodium_approx_range, c.sodiumLowMg.roundToInt(), c.sodiumHighMg.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (rec.highSodiumUncertainty) {
                    Text(
                        stringResource(R.string.nutrition_sodium_uncertain_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        } else {
            Text(
                stringResource(R.string.nutrition_food_not_in_db),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
    val barcodeBasis by vm.barcodeBasis.collectAsStateWithLifecycle()
    val isSaving by vm.isSaving.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    // Editing an id that no longer exists: dismiss instead of saving a blank row.
    LaunchedEffect(notFound) { if (notFound) onCancel() }
    val isEditing = idArg != null
    var showDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(
                    if (isEditing) R.string.nutrition_confirm_edit_title
                    else R.string.nutrition_confirm_new_title
                ),
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
                        enabled = draft?.description?.isNotBlank() == true && !isSaving,
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

            // Barcode basis hint: the label only had per-100g data, so the
            // numbers below are NOT one serving unless they could be scaled.
            when (barcodeBasis) {
                NutrimentBasis.Per100g -> Text(
                    stringResource(R.string.nutrition_barcode_per_100g_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                NutrimentBasis.ScaledPer100g -> Text(
                    stringResource(R.string.nutrition_barcode_scaled_serving_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }

            StandardCard(title = stringResource(R.string.nutrition_meal_label)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                    MealType.entries.forEach { mt ->
                        ExpressiveFilterChip(
                            selected = d.mealType == mt,
                            onClick = { vm.update { it.copy(mealType = mt) } },
                            label = stringResource(mealTypeLabel(mt)),
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
                        ExpressiveFilterChip(
                            selected = d.sodiumLevel == lvl,
                            onClick = { vm.update { it.copy(sodiumLevel = lvl) } },
                            label = stringResource(sodiumLevelLabel(lvl)),
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

            // Prominent primary save — the Expressive CTA mirrors the TopAppBar
            // save action (same vm.save call) for a big, obvious confirmation
            // target at the end of the form.
            ExpressivePrimaryButton(
                text = stringResource(R.string.save),
                onClick = { vm.save(onSaved) },
                enabled = d.description.isNotBlank() && !isSaving,
                icon = Icons.Filled.Check,
                fillWidth = true,
            )

            Spacer(Modifier.size(AppSpacing.itemGap))
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
