package com.silverbp.android.ui.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.nutrition.FoodItem
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.NutritionDatabase
import com.silverbp.android.nutrition.NutritionInputMethod
import com.silverbp.android.nutrition.NutritionRepository
import com.silverbp.android.nutrition.Portion
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.nutrition.SodiumSource
import com.silverbp.android.nutrition.compute
import com.silverbp.android.nutrition.currentMealType
import com.silverbp.android.recognition.ExtractedFoodItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** How a recognised item's nutrition was resolved — drives the per-item UI. */
enum class ItemState { Matched, Approx, Manual, Unknown }

/**
 * One recognised food after resolving it against [NutritionDatabase] + the
 * user's edits. Computed by [resolveFoodItem] in the screen (recomputes live)
 * and passed back to [NutritionConfirmViewModel.saveRecognizedMeal] so the two
 * agree exactly. Macros are null when unknown and not manually entered — the
 * item is still kept (never silently dropped).
 */
data class ResolvedFoodItem(
    val grams: Double,
    val kcal: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val carbG: Double?,
    val sodiumMg: Double?,
    val sodiumLowMg: Double?,
    val sodiumHighMg: Double?,
    val state: ItemState,
    /** Default serving grams of the matched record (drives S/M/L presets); null when unmatched. */
    val defaultPortionGrams: Double?,
    val highSodiumUncertainty: Boolean,
)

/**
 * Resolve one recognised item. [gramsOverride] applies to matched items (the
 * gram field / S/M/L presets); [manualKcal] applies to unmatched items the user
 * typed a calorie value for. Pure — safe to call during recomposition.
 */
fun resolveFoodItem(
    item: ExtractedFoodItem,
    gramsOverride: Double?,
    manualKcal: Double?,
): ResolvedFoodItem {
    val m = NutritionDatabase.matchBestEffort(item.name, item.nameEn)
    if (m != null) {
        val def = m.record.defaultPortionGrams
        val grams = gramsOverride ?: Portion.fromHint(item.portionHint).grams(def)
        val c = m.record.compute(grams)
        return ResolvedFoodItem(
            grams = grams,
            kcal = c.kcal, proteinG = c.proteinG, fatG = c.fatG, carbG = c.carbG,
            sodiumMg = c.sodiumMg, sodiumLowMg = c.sodiumLowMg, sodiumHighMg = c.sodiumHighMg,
            state = if (m.approximate) ItemState.Approx else ItemState.Matched,
            defaultPortionGrams = def,
            highSodiumUncertainty = m.record.highSodiumUncertainty,
        )
    }
    // No DB match — keep the item; the user (or an online lookup) can fill calories.
    return ResolvedFoodItem(
        grams = gramsOverride ?: 0.0,
        kcal = manualKcal, proteinG = null, fatG = null, carbG = null,
        sodiumMg = null, sodiumLowMg = null, sodiumHighMg = null,
        state = if (manualKcal != null) ItemState.Manual else ItemState.Unknown,
        defaultPortionGrams = null,
        highSodiumUncertainty = false,
    )
}

/**
 * Backs [NutritionConfirmScreen] in two modes:
 *  - **recognized** ([meal] non-null): a photo-identified meal — the screen
 *    picks portions and we compute nutrition from [NutritionDatabase] on save.
 *  - **flat** ([draft] non-null): a barcode/manual draft or an existing row
 *    being edited — plain editable fields.
 */
class NutritionConfirmViewModel(
    private val repo: NutritionRepository = ServiceLocator.nutritionRepository,
) : ViewModel() {

    private val _draft = MutableStateFlow<FoodLog?>(null)
    val draft: StateFlow<FoodLog?> = _draft.asStateFlow()

    private val _meal = MutableStateFlow<RecognizedMeal?>(null)
    val meal: StateFlow<RecognizedMeal?> = _meal.asStateFlow()

    private var initialized = false

    fun init(idArg: String?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            if (idArg != null) {
                _draft.value = repo.findById(UUID.fromString(idArg)) ?: FoodLog()
            } else {
                val m = NutritionDraftHolder.takeMeal()
                if (m != null) _meal.value = m
                else _draft.value = NutritionDraftHolder.take() ?: FoodLog()
            }
        }
    }

    // ---- Flat mode (barcode / manual / edit existing) ----

    fun update(transform: (FoodLog) -> FoodLog) {
        _draft.value = _draft.value?.let(transform)
    }

    fun save(onSaved: () -> Unit) {
        val d = _draft.value ?: return
        viewModelScope.launch {
            repo.upsert(d)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val d = _draft.value ?: return
        viewModelScope.launch {
            repo.delete(d.id)
            onDeleted()
        }
    }

    // ---- Recognized mode (photo) ----

    /**
     * Build a [FoodLog] from the recognised foods + the screen's per-item
     * [ResolvedFoodItem]s (matched / approximate / manual / unknown) and save.
     * **Every** item is kept — unmatched ones with null macros — so a meal is
     * never silently dropped and shows up in history (and the coach context)
     * even when calories are still unknown.
     */
    fun saveRecognizedMeal(
        meal: RecognizedMeal,
        resolved: List<ResolvedFoodItem>,
        onSaved: () -> Unit,
    ) {
        if (meal.items.isEmpty()) { onSaved(); return }
        viewModelScope.launch {
            val items = meal.items.mapIndexed { idx, ex ->
                val r = resolved.getOrNull(idx)
                FoodItem(
                    name = ex.name,
                    nameEn = ex.nameEn,
                    grams = r?.grams?.takeIf { it > 0 },
                    caloriesKcal = r?.kcal,
                    sodiumMg = r?.sodiumMg,
                    proteinG = r?.proteinG,
                    carbsG = r?.carbG,
                    fatG = r?.fatG,
                )
            }
            val kcal = resolved.sumOf { it.kcal ?: 0.0 }
            val protein = resolved.sumOf { it.proteinG ?: 0.0 }
            val carb = resolved.sumOf { it.carbG ?: 0.0 }
            val fat = resolved.sumOf { it.fatG ?: 0.0 }
            val sodEst = resolved.sumOf { it.sodiumMg ?: 0.0 }
            val sodLo = resolved.sumOf { it.sodiumLowMg ?: 0.0 }
            val sodHi = resolved.sumOf { it.sodiumHighMg ?: 0.0 }
            val log = FoodLog(
                timestamp = Instant.now(),
                mealType = currentMealType(),
                inputMethod = NutritionInputMethod.Photo,
                description = items.joinToString("、") { it.name },
                photoFilename = meal.photoFilename,
                items = items,
                calories = kcal,
                proteinG = protein,
                carbsG = carb,
                fatG = fat,
                sodiumMg = sodEst,
                sodiumMgLow = sodLo,
                sodiumMgHigh = sodHi,
                sodiumLevel = SodiumLevel.forMealMg(sodEst),
                sodiumSource = SodiumSource.Estimate,
                confidence = meal.overallConfidence ?: 0.5,
                analysisBackend = meal.backendTag,
            )
            repo.upsert(log)
            onSaved()
        }
    }
}
