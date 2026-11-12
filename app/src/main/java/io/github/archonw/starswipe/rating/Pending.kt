package io.github.archonw.starswipe.rating

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class PendingKind { RATE, RESTORE }

/**
 * Ein Schreibauftrag für eine Sidecar. Erledigte Aufträge bleiben eine Weile stehen,
 * damit „Rückgängig“ den vorherigen Inhalt ([beforeBytes]) wiederherstellen kann.
 */
@Entity(tableName = "pending_ratings")
data class PendingRatingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storageId: Long,
    val photoPath: String,
    val kind: PendingKind,
    val rating: Int?,
    /** Lokales Rating vor diesem Auftrag. */
    val previousRating: Int?,
    val createdAt: Long,
    val done: Boolean = false,
    val sidecarPath: String? = null,
    val beforeBytes: ByteArray? = null,
    val beforeExisted: Boolean = false,
    val writtenEtag: String? = null,
    val writtenModified: Long? = null,
    val writtenSize: Long? = null,
    val attempts: Int = 0,
)

@Dao
interface PendingRatingDao {
    @Insert suspend fun insert(p: PendingRatingEntity): Long
    @Update suspend fun update(p: PendingRatingEntity)
    @Query("DELETE FROM pending_ratings WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM pending_ratings WHERE id = :id") suspend fun get(id: Long): PendingRatingEntity?
    @Query("SELECT * FROM pending_ratings WHERE done = 0 ORDER BY id") suspend fun open(): List<PendingRatingEntity>
    @Query("SELECT COUNT(*) FROM pending_ratings WHERE done = 0") fun openCount(): Flow<Int>
    @Query("SELECT storageId, COUNT(*) AS count FROM pending_ratings WHERE done = 0 GROUP BY storageId")
    fun openCounts(): Flow<List<io.github.archonw.starswipe.db.StorageCount>>
    @Query("SELECT photoPath FROM pending_ratings WHERE done = 0 AND storageId = :storageId")
    suspend fun openPaths(storageId: Long): List<String>
    @Query("SELECT COUNT(*) FROM pending_ratings WHERE done = 0 AND storageId IN (:ids)")
    suspend fun openCountFor(ids: List<Long>): Int
    @Query("DELETE FROM pending_ratings WHERE done = 1 AND id NOT IN (SELECT id FROM pending_ratings WHERE done = 1 ORDER BY id DESC LIMIT 100)")
    suspend fun prune()
    @Query("DELETE FROM pending_ratings WHERE storageId = :storageId") suspend fun deleteByStorage(storageId: Long)
}
