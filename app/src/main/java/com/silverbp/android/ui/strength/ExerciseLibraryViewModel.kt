package com.silverbp.android.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.strength.BodyPart
import com.silverbp.android.strength.ExerciseCatalogItem
import com.silverbp.android.strength.ExerciseLibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExerciseLibraryUiState(
    val query: String = "",
    // null = 全部 (no body-part filter).
    val bodyPart: BodyPart? = null,
    val savedOnly: Boolean = false,
    val items: List<ExerciseCatalogItem> = emptyList(),
)

class ExerciseLibraryViewModel(
    private val repo: ExerciseLibraryRepository = ServiceLocator.exerciseLibraryRepository,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val bodyPartFlow = MutableStateFlow<BodyPart?>(null)
    private val savedOnlyFlow = MutableStateFlow(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<ExerciseLibraryUiState> = combine(
        queryFlow,
        bodyPartFlow,
        savedOnlyFlow,
    ) { query, bodyPart, savedOnly -> Triple(query, bodyPart, savedOnly) }
        .flatMapLatest { (query, bodyPart, savedOnly) ->
            val source = when {
                query.isNotBlank() -> repo.search(query)
                savedOnly -> repo.observeFavorites()
                bodyPart != null -> repo.observeByBodyPart(bodyPart)
                else -> repo.observeAll()
            }
            source.map { items ->
                // Compose the active filters on top of the chosen source so a
                // text search still respects the body-part chip / saved toggle.
                val narrowed = items
                    .let { if (bodyPart != null) it.filter { e -> e.bodyPart == bodyPart } else it }
                    .let { if (savedOnly) it.filter { e -> e.isFavorite } else it }
                ExerciseLibraryUiState(query, bodyPart, savedOnly, narrowed)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseLibraryUiState())

    init {
        viewModelScope.launch { ServiceLocator.ensureSeeded() }
    }

    fun setQuery(q: String) { queryFlow.value = q }

    fun setBodyPart(part: BodyPart?) { bodyPartFlow.value = part }

    fun setSavedOnly(saved: Boolean) { savedOnlyFlow.value = saved }

    fun toggleFavorite(item: ExerciseCatalogItem) {
        viewModelScope.launch { repo.setFavorite(item.id, !item.isFavorite) }
    }
}
