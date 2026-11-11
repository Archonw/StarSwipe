package io.github.archonw.starswipe.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

fun testDb(): AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
    .allowMainThreadQueries().build()

fun location(id: Long = 0, type: StorageType = StorageType.SMB, folders: List<String> = listOf("Album"),
             enabled: Boolean = true, oneStar: Boolean = false, name: String = "Test") =
    StorageLocationEntity(id, type, name, "{}", folders, enabled, oneStar)

fun photo(storageId: Long, path: String, takenAt: Long, rating: Int? = null, broken: Boolean = false) =
    PhotoEntity(storageId, path, path.substringAfterLast('/'), path.substringBeforeLast('/', ""), takenAt,
        null, 0, 1, null, null, null, null, null, rating, broken, 0)
