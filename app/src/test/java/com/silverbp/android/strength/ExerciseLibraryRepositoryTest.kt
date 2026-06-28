package com.silverbp.android.strength

import com.silverbp.android.core.db.ExerciseCatalogItemEntity
import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseLibraryRepositoryTest {

    @Test
    fun upsert_stamps_catalog_item_with_hlc() = runTest {
        val dao = FakeLibraryDao()
        val hlc = Hlc.of(1_000L, 0, 1L).packed
        val repo = ExerciseLibraryRepository(dao, FakeLocalSyncWriter(hlc))

        repo.upsert(
            ExerciseCatalogItem(
                id = "catalog-001",
                name = "Bench Press",
                bodyPart = BodyPart.UpperBody,
                muscleGroups = listOf("chest"),
                description = "",
            ),
        )

        assertEquals(hlc, dao.rows.getValue("catalog-001").hlcUpdatedAt)
    }

    @Test
    fun set_favorite_stamps_hlc() = runTest {
        val dao = FakeLibraryDao().apply {
            upsert(
                ExerciseCatalogItemEntity(
                    id = "catalog-001",
                    name = "Bench Press",
                    bodyPart = BodyPart.UpperBody.raw,
                    muscleGroupsJson = "[]",
                    description = "",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }
        val hlc = Hlc.of(1_000L, 0, 1L).packed
        val repo = ExerciseLibraryRepository(dao, FakeLocalSyncWriter(hlc))

        repo.setFavorite("catalog-001", true)

        val row = dao.rows.getValue("catalog-001")
        assertEquals(true, row.isFavorite)
        assertEquals(hlc, row.hlcUpdatedAt)
    }

    private class FakeLibraryDao : ExerciseLibraryDao {
        val rows = mutableMapOf<String, ExerciseCatalogItemEntity>()
        override fun observeAll(): Flow<List<ExerciseCatalogItemEntity>> =
            flowOf(rows.values.sortedBy { it.name })
        override suspend fun allOnce(): List<ExerciseCatalogItemEntity> = rows.values.sortedBy { it.name }
        override fun observeFavorites(): Flow<List<ExerciseCatalogItemEntity>> =
            flowOf(rows.values.filter { it.isFavorite }.sortedBy { it.name })
        override fun search(q: String): Flow<List<ExerciseCatalogItemEntity>> =
            flowOf(rows.values.filter { it.name.contains(q) }.sortedBy { it.name })
        override fun observeByBodyPart(raw: String): Flow<List<ExerciseCatalogItemEntity>> =
            flowOf(rows.values.filter { it.bodyPart == raw }.sortedBy { it.name })
        override suspend fun setFavorite(id: String, fav: Boolean) {
            rows[id]?.let { rows[id] = it.copy(isFavorite = fav) }
        }
        override suspend fun setFavoriteWithHlc(id: String, fav: Boolean, hlc: String) {
            rows[id]?.let { rows[id] = it.copy(isFavorite = fav, hlcUpdatedAt = hlc) }
        }
        override suspend fun upsert(item: ExerciseCatalogItemEntity) {
            rows[item.id] = item
        }
        override suspend fun upsertAll(items: List<ExerciseCatalogItemEntity>) {
            items.forEach { rows[it.id] = it }
        }
        override suspend fun count(): Int = rows.size
    }

    private class FakeLocalSyncWriter(private val hlc: String) : LocalSyncWriter {
        override fun nextHlc(): String = hlc
        override suspend fun delete(type: SyncEntityType, pk: String) = error("unused")
        override suspend fun stamp(type: SyncEntityType, pk: String) {}
    }
}
