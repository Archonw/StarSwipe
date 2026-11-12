package io.github.archonw.starswipe.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StorageLocationDao {
    @Insert suspend fun insert(l: StorageLocationEntity): Long
    @Update suspend fun update(l: StorageLocationEntity)
    @Query("DELETE FROM storage_locations WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM storage_locations WHERE id = :id") suspend fun get(id: Long): StorageLocationEntity?
    @Query("SELECT * FROM storage_locations ORDER BY name") fun observeAll(): Flow<List<StorageLocationEntity>>
    @Query("SELECT * FROM storage_locations ORDER BY name") suspend fun all(): List<StorageLocationEntity>
}

data class StorageCount(val storageId: Long, val count: Int)

@Dao
interface PhotoDao {
    companion object { const val ALL = -1L }

    @Upsert suspend fun upsert(p: List<PhotoEntity>)

    @Query(
        """SELECT p.* FROM photos p JOIN storage_locations s ON s.id = p.storageId
           WHERE s.enabled = 1 AND p.sidecarBroken = 0 AND (:only = -1 OR p.storageId = :only)
             AND (:showRated OR p.rating IS NULL OR (s.oneStarIsUnrated = 1 AND p.rating = 1))
           ORDER BY p.takenAt DESC, p.path"""
    )
    fun queue(showRated: Boolean, only: Long): Flow<List<PhotoEntity>>

    @Query(
        """SELECT p.storageId AS storageId, COUNT(*) AS count FROM photos p JOIN storage_locations s ON s.id = p.storageId
           WHERE p.sidecarBroken = 0 AND (p.rating IS NULL OR (s.oneStarIsUnrated = 1 AND p.rating = 1))
           GROUP BY p.storageId"""
    )
    fun unratedCounts(): Flow<List<StorageCount>>

    @Query("SELECT * FROM photos WHERE storageId = :storageId") suspend fun byStorage(storageId: Long): List<PhotoEntity>
    @Query("SELECT COUNT(*) FROM photos WHERE storageId = :storageId") suspend fun countFor(storageId: Long): Int
    @Query("SELECT * FROM photos WHERE storageId = :storageId AND folder = :folder")
    suspend fun byFolder(storageId: Long, folder: String): List<PhotoEntity>
    @Query("SELECT * FROM photos WHERE storageId = :storageId AND path = :path") suspend fun get(storageId: Long, path: String): PhotoEntity?
    @Query("DELETE FROM photos WHERE storageId = :storageId AND seen != :run") suspend fun deleteNotSeen(storageId: Long, run: Long)
    @Query("DELETE FROM photos WHERE storageId = :storageId AND path = :path") suspend fun delete(storageId: Long, path: String)
    @Query("DELETE FROM photos WHERE storageId = :storageId") suspend fun deleteByStorage(storageId: Long)
    @Query("UPDATE photos SET sidecarBroken = 1 WHERE storageId = :storageId AND path = :path")
    suspend fun markBroken(storageId: Long, path: String)
    @Query("UPDATE photos SET rating = :rating WHERE storageId = :storageId AND path = :path")
    suspend fun updateRating(storageId: Long, path: String, rating: Int?)
    @Query("""UPDATE photos SET sidecarPath = :sidecarPath, sidecarEtag = :etag, sidecarModified = :modified,
              sidecarSize = :size, sidecarBroken = :broken WHERE storageId = :storageId AND path = :path""")
    suspend fun updateSidecar(storageId: Long, path: String, sidecarPath: String?, etag: String?, modified: Long?, size: Long?, broken: Boolean)
}
