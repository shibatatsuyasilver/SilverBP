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
 */
class ExerciseLibraryRepository(
    private val dao: ExerciseLibraryDao,
) {

    fun observeAll(): Flow<List<ExerciseCatalogItem>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeFavorites(): Flow<List<ExerciseCatalogItem>> =
        dao.observeFavorites().map { list -> list.map { it.toDomain() } }

    fun search(query: String): Flow<List<ExerciseCatalogItem>> =
        dao.search(query).map { list -> list.map { it.toDomain() } }

    fun observeByBodyPart(bodyPart: BodyPart): Flow<List<ExerciseCatalogItem>> =
        dao.observeByBodyPart(bodyPart.raw).map { list -> list.map { it.toDomain() } }

    suspend fun setFavorite(id: String, favorite: Boolean) =
        dao.setFavorite(id, favorite)

    suspend fun upsert(item: ExerciseCatalogItem) {
        val now = System.currentTimeMillis()
        dao.upsert(item.toEntity(createdAt = now, updatedAt = now))
    }

    suspend fun count(): Int = dao.count()
}
