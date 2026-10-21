package be.verdonckit.zapper.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Eén titel van één provider. `key` = "<providerId>:<itemId>". */
@Entity(tableName = "items", primaryKeys = ["key"], indices = [Index("providerId"), Index("dedupKey")])
data class ItemEntity(
    val key: String,
    val providerId: String,
    val itemId: String,
    val kind: String,
    val title: String,
    val description: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val playableId: String?,
    val year: Int?,
    /** Genormaliseerde titel(+jaar) om dezelfde reeks over providers heen samen te nemen. */
    val dedupKey: String,
    /** Positie in de lijst van de provider; sorteren hierop mengt providers vanzelf. */
    val rank: Int,
    val syncedAt: Long,
)

@Entity(tableName = "item_categories", primaryKeys = ["itemKey", "category"], indices = [Index("category")])
data class ItemCategoryEntity(
    val itemKey: String,
    val category: String,
    /** Positie binnen deze categorie bij de zender. Rijen sorteren hierop: positie 0 van elke zender eerst. */
    val position: Int,
)

@Entity(tableName = "progress", primaryKeys = ["playableKey"])
data class ProgressEntity(
    /** "<providerId>:<playableId>" */
    val playableKey: String,
    val providerId: String,
    val playableId: String,
    val itemKey: String?,
    val title: String,
    val imageUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface CatalogDao {

    /**
     * Eén rij op het startscherm. Sorteert op de positie binnen de categorie, zodat zenders
     * om beurten verschijnen. Bij dezelfde titel op meerdere zenders kiest SQLite de rij met
     * de laagste score (MIN met bare columns); een versie zonder beeld krijgt een strafpunt.
     */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT items.*, MIN(ic.position + CASE WHEN items.backdropUrl IS NULL AND items.posterUrl IS NULL THEN 1000000 ELSE 0 END) AS score
        FROM items
        JOIN item_categories AS ic ON ic.itemKey = items.key
        WHERE ic.category = :category
        GROUP BY items.dedupKey
        ORDER BY score, items.title
        LIMIT :limit
        """,
    )
    fun rowFor(category: String, limit: Int = 40): Flow<List<ItemEntity>>

    @Query("SELECT DISTINCT category FROM item_categories")
    fun categoriesWithContent(): Flow<List<String>>

    @Query("SELECT * FROM items WHERE key = :key")
    suspend fun item(key: String): ItemEntity?

    @Query("SELECT * FROM items WHERE dedupKey = :dedupKey ORDER BY providerId")
    suspend fun sameTitle(dedupKey: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE title LIKE '%' || :q || '%' GROUP BY dedupKey ORDER BY rank LIMIT 60")
    fun search(q: String): Flow<List<ItemEntity>>

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    @Query("DELETE FROM items WHERE providerId = :providerId")
    suspend fun deleteItems(providerId: String)

    @Query("DELETE FROM item_categories WHERE itemKey LIKE :providerId || ':%'")
    suspend fun deleteCategories(providerId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<ItemCategoryEntity>)

    /** Atomisch vervangen: de UI ziet nooit een halve catalogus. */
    @Transaction
    suspend fun replaceProvider(providerId: String, items: List<ItemEntity>, categories: List<ItemCategoryEntity>) {
        deleteCategories(providerId)
        deleteItems(providerId)
        insertItems(items)
        insertCategories(categories)
    }

    // ---- voortgang ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE playableKey = :playableKey")
    suspend fun progress(playableKey: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE providerId = :providerId AND playableId IN (:playableIds)")
    suspend fun progressFor(providerId: String, playableIds: List<String>): List<ProgressEntity>

    @Query(
        """
        SELECT * FROM progress
        WHERE positionMs > 60000 AND (durationMs = 0 OR positionMs < durationMs * 0.92)
        ORDER BY updatedAt DESC LIMIT 20
        """,
    )
    fun continueWatching(): Flow<List<ProgressEntity>>
}

@Database(
    entities = [ItemEntity::class, ItemCategoryEntity::class, ProgressEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ZapperDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, ZapperDatabase::class.java, "zapper.db")
                // Index is volledig opnieuw op te bouwen; enkel voortgang gaat verloren bij schemawijziging.
                .fallbackToDestructiveMigration()
                .build()
    }
}
