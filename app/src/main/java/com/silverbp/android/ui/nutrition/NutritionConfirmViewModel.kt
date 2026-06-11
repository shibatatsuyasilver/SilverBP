package com.silverbp.android.ui.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.nutrition.FoodItem
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.NutrimentBasis
import com.silverbp.android.nutrition.NutritionDatabase
import com.silverbp.android.nutrition.NutritionInputMethod
import com.silverbp.android.nutrition.NutritionRepository
import com.silverbp.android.nutrition.Portion
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.nutrition.SodiumSource
import com.silverbp.android.nutrition.compute
import com.silverbp.android.nutrition.currentMealType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

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

    /** Basis of a freshly scanned barcode draft — drives the per-100g hint. */
    private val _barcodeBasis = MutableStateFlow<NutrimentBasis?>(null)
    val barcodeBasis: StateFlow<NutrimentBasis?> = _barcodeBasis.asStateFlow()

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
                else {
                    _draft.value = NutritionDraftHolder.take() ?: FoodLog()
                    _barcodeBasis.value = BarcodeBasisHolder.take()
                }
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
     * Build a [FoodLog] from the recognised foods + chosen portions — each item
     * matched to [NutritionDatabase] and computed at its portion — then save.
     * Items with no DB match are skipped (mirrors iOS).
     */
    fun saveRecognizedMeal(
        meal: RecognizedMeal,
        portions: Map<Int, Portion>,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            val items = ArrayList<FoodItem>()
            var kcal = 0.0; var protein = 0.0; var carb = 0.0; var fat = 0.0
            var sodEst = 0.0; var sodLo = 0.0; var sodHi = 0.0
            meal.items.forEachIndexed { idx, ex ->
                val rec = NutritionDatabase.match(ex.name, ex.nameEn) ?: return@forEachIndexed
                val c = rec.compute(portions[idx] ?: Portion.fromHint(ex.portionHint))
                kcal += c.kcal; protein += c.proteinG; carb += c.carbG; fat += c.fatG
                sodEst += c.sodiumMg; sodLo += c.sodiumLowMg; sodHi += c.sodiumHighMg
                items += FoodItem(
                    name = ex.name,
                    nameEn = ex.nameEn,
                    grams = c.grams,
                    caloriesKcal = c.kcal,
                    sodiumMg = c.sodiumMg,
                    proteinG = c.proteinG,
                    carbsG = c.carbG,
                    fatG = c.fatG,
                )
            }
            if (items.isEmpty()) { onSaved(); return@launch }
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
