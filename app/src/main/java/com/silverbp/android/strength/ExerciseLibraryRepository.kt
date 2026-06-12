package com.silverbp.android.strength

import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read+write access to the exercise move library. Mirrors
 * [com.silverbp.android.exercise.ExerciseRepository] conventions: Flow reads,
 * suspend writes, mapping inside the repo.
 *
 * [toDomain] localizes the display name/muscles to the current app language, so
 * search and sort run in-memory on the localized name (the DB stores canonical
 * Chinese; a `name LIKE` / `ORDER BY name` query there would only match Chinese).
 */
class ExerciseLibraryRepository(
    private val dao: ExerciseLibraryDao,
) {

    private fun List<ExerciseCatalogItem>.sortedByName() = sortedBy { it.name }

    fun observeAll(): Flow<List<ExerciseCatalogItem>> =
        dao.observeAll().map { list -> list.map { it.toDomain().localized() }.sortedByName() }

    fun observeFavorites(): Flow<List<ExerciseCatalogItem>> =
        dao.observeFavorites().map { list -> list.map { it.toDomain().localized() }.sortedByName() }

    fun search(query: String): Flow<List<ExerciseCatalogItem>> =
        dao.observeAll().map { list ->
            list.map { it.toDomain().localized() }
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedByName()
        }

    fun observeByBodyPart(bodyPart: BodyPart): Flow<List<ExerciseCatalogItem>> =
        dao.observeByBodyPart(bodyPart.raw).map { list -> list.map { it.toDomain().localized() }.sortedByName() }

    suspend fun setFavorite(id: String, favorite: Boolean) =
        dao.setFavorite(id, favorite)

    suspend fun upsert(item: ExerciseCatalogItem) {
        val now = System.currentTimeMillis()
        dao.upsert(item.toEntity(createdAt = now, updatedAt = now))
    }

    suspend fun count(): Int = dao.count()
}
