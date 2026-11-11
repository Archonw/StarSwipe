package io.github.archonw.starswipe.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class StorageType { NEXTCLOUD, WEBDAV, SMB, LOCAL }

@Entity(tableName = "storage_locations")
data class StorageLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val type: StorageType,
    val name: String,
    /** JSON ohne Geheimnisse, Felder je Typ siehe StorageFactory. */
    val config: String,
    val folders: List<String>,
    val enabled: Boolean,
    val oneStarIsUnrated: Boolean,
)

@Entity(tableName = "photos", primaryKeys = ["storageId", "path"])
data class PhotoEntity(
    val storageId: Long,
    val path: String,
    val name: String,
    val folder: String,
    /** Aufnahmezeit, epoch ms. */
    val takenAt: Long,
    val etag: String?,
    val modified: Long,
    val size: Long,
    val fileId: Long?,
    val sidecarPath: String?,
    val sidecarEtag: String?,
    val sidecarModified: Long?,
    val sidecarSize: Long?,
    val rating: Int?,
    val sidecarBroken: Boolean,
    /** Kennung des Scan-Laufs, in dem das Foto zuletzt gesehen wurde. */
    val seen: Long,
)

class Converters {
    private val list = ListSerializer(String.serializer())
    @TypeConverter fun fromList(v: List<String>): String = Json.encodeToString(list, v)
    @TypeConverter fun toList(v: String): List<String> = Json.decodeFromString(list, v)
    @TypeConverter fun fromType(v: StorageType): String = v.name
    @TypeConverter fun toType(v: String): StorageType = StorageType.valueOf(v)
}
