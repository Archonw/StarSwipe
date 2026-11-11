package io.github.archonw.starswipe.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.archonw.starswipe.rating.PendingRatingDao
import io.github.archonw.starswipe.rating.PendingRatingEntity

@Database(entities = [StorageLocationEntity::class, PhotoEntity::class, PendingRatingEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locations(): StorageLocationDao
    abstract fun photos(): PhotoDao
    abstract fun pending(): PendingRatingDao
}
