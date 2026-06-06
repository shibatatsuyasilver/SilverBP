package com.silverbp.android.ui.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.NutritionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Backs [NutritionConfirmScreen]. Loads the staged draft (new capture) from
 * [NutritionDraftHolder], or an existing row by id (edit), and applies edits
 * before saving via [NutritionRepository].
 */
class NutritionConfirmViewModel(
    private val repo: NutritionRepository = ServiceLocator.nutritionRepository,
) : ViewModel() {

    private val _draft = MutableStateFlow<FoodLog?>(null)
    val draft: StateFlow<FoodLog?> = _draft.asStateFlow()

    fun init(idArg: String?) {
        if (_draft.value != null) return
        viewModelScope.launch {
            _draft.value = if (idArg != null) {
                repo.findById(UUID.fromString(idArg)) ?: FoodLog()
            } else {
                NutritionDraftHolder.take() ?: FoodLog()
            }
        }
    }

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
}
