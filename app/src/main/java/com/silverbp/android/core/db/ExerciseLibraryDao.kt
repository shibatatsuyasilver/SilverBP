package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read+write API for [ExerciseCatalogItemEntity] (the seeded move library +
 * favorites). Mirrors [ExerciseDao] conventions: Flow for reads, suspend for
 * writes; entity types only (mapping happens in
 * [com.silverbp.android.strength.ExerciseLibraryRepository]).
 */
@Dao
interface ExerciseLibraryDao {

    @Query("SELECT * FROM exercise_catalog_item ORDER BY name ASC")
    fun observeAll(): Flow<List<ExerciseCatalogItemEntity>>

    @Query("SELECT * FROM exercise_catalog_item")
    suspend fun allOnce(): List<ExerciseCatalogItemEntity>

    @Query("SELECT * FROM exercise_catalog_item WHERE isFavorite = 1 ORDER BY name ASC")
    fun observeFavorites(): Flow<List<ExerciseCatalogItemEntity>>

    @Query(
        "SELECT * FROM exercise_catalog_item " +
            "WHERE name LIKE '%' || :q || '%' " +
            "ORDER BY name ASC"
    )
    fun search(q: String): Flow<List<ExerciseCatalogItemEntity>>

    @Query("SELECT * FROM exercise_catalog_item WHERE bodyPart = :raw ORDER BY name ASC")
    fun observeByBodyPart(raw: String): Flow<List<ExerciseCatalogItemEntity>>

    @Query("UPDATE exercise_catalog_item SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("UPDATE exercise_catalog_item SET isFavorite = :fav, hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun setFavoriteWithHlc(id: String, fav: Boolean, hlc: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ExerciseCatalogItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ExerciseCatalogItemEntity>)

    @Query("SELECT COUNT(*) FROM exercise_catalog_item")
    suspend fun count(): Int
}
