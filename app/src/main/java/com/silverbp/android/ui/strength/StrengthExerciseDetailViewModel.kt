package com.silverbp.android.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.strength.ExerciseCatalogItem
import com.silverbp.android.strength.ExerciseLibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StrengthExerciseDetailViewModel(
    private val exerciseId: String,
    private val repo: ExerciseLibraryRepository = ServiceLocator.exerciseLibraryRepository,
) : ViewModel() {

    // Observe the whole catalog and project the single item so the favorite
    // tag updates live after a toggle. The catalog is small (seeded ~36 moves).
    val item: StateFlow<ExerciseCatalogItem?> =
        repo.observeAll()
            .map { list -> list.firstOrNull { it.id == exerciseId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleFavorite() {
        val cur = item.value ?: return
        viewModelScope.launch { repo.setFavorite(cur.id, !cur.isFavorite) }
    }
}
