package dev.prowsee.permanent.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "history", indices = [Index("visitedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun all(): List<BookmarkEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeExists(url: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    suspend fun exists(url: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM bookmarks")
    suspend fun clear()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE title LIKE '%' || :q || '%' OR url LIKE '%' || :q || '%' ORDER BY visitedAt DESC LIMIT 100")
    fun search(q: String): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE visitedAt < :cutoff")
    suspend fun trimOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}

@Database(
    entities = [BookmarkEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ProwseeDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
    abstract fun history(): HistoryDao

    companion object {
        @Volatile
        private var instance: ProwseeDatabase? = null

        fun get(context: Context): ProwseeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ProwseeDatabase::class.java,
                "prowsee.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}

class BrowsingRepository(private val db: ProwseeDatabase) {

    val bookmarks: Flow<List<BookmarkEntity>> = db.bookmarks().observeAll()
    val history: Flow<List<HistoryEntity>> = db.history().observeRecent()

    fun isBookmarked(url: String): Flow<Boolean> = db.bookmarks().observeExists(url)

    suspend fun addBookmark(url: String, title: String) =
        db.bookmarks().insert(BookmarkEntity(url = url, title = title.ifBlank { url }))

    suspend fun removeBookmark(url: String) = db.bookmarks().delete(url)

    suspend fun toggleBookmark(url: String, title: String): Boolean {
        return if (db.bookmarks().exists(url)) {
            db.bookmarks().delete(url)
            false
        } else {
            db.bookmarks().insert(BookmarkEntity(url = url, title = title.ifBlank { url }))
            true
        }
    }

    suspend fun recordVisit(url: String, title: String) {
        if (url.isBlank() || !url.startsWith("http")) return
        db.history().insert(HistoryEntity(url = url, title = title.ifBlank { url }))
        // Keep history bounded without user-visible pruning.
        if (db.history().count() > 5000) {
            db.history().trimOlderThan(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
        }
    }

    suspend fun clearHistory() = db.history().clear()
    suspend fun clearBookmarks() = db.bookmarks().clear()
}
