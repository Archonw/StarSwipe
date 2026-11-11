package io.github.archonw.starswipe.storage

import java.time.Instant
import java.time.LocalDateTime

/** Ein Eintrag in einem Speicherort. [path] ist relativ zur Wurzel, mit '/' getrennt, unkodiert. */
data class Entry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Instant,
    val etag: String?,
    val contentType: String?,
    val fileId: Long?,
    val takenAt: LocalDateTime?,
) {
    val signature: Signature get() = Signature(etag, modified, size)
}

data class Signature(val etag: String?, val modified: Instant, val size: Long) {
    /** ETag gleich, wenn beide vorhanden; sonst Änderungszeit und Größe gleich. */
    fun matches(e: Entry): Boolean =
        if (etag != null && e.etag != null) etag == e.etag
        else modified == e.modified && size == e.size
}

sealed class StorageException(msg: String, cause: Throwable? = null) : Exception(msg, cause) {
    class Unauthorized : StorageException("unauthorized")
    class NotFound(path: String) : StorageException(path)
    /** Bedingung beim Schreiben/Löschen verletzt. */
    class Conflict(path: String) : StorageException(path)
    class Unreachable(cause: Throwable) : StorageException(cause.toString(), cause)
    class Other(msg: String) : StorageException(msg)
    /** Speicherort ohne gewählten Ordner. */
    class NoFolders : StorageException("no folders chosen")
}

sealed interface PreviewSource {
    data class Http(val url: String, val headers: Map<String, String>) : PreviewSource
    class Bytes(val key: String, val load: suspend () -> ByteArray) : PreviewSource
    data class ContentUri(val uri: String) : PreviewSource
}

interface PhotoStorage {
    /** Nur direkte Kinder; "" = Wurzel. */
    suspend fun list(dir: String): List<Entry>
    suspend fun read(path: String, maxBytes: Long? = null): ByteArray
    /** null = existiert nicht. */
    suspend fun stat(path: String): Entry?
    /** [expected] == null: Datei darf nicht existieren; sonst muss die Signatur passen, sonst [StorageException.Conflict]. */
    suspend fun write(path: String, data: ByteArray, expected: Signature?): Signature
    suspend fun delete(path: String, expected: Signature?)
    fun previewRequest(entry: Entry): PreviewSource
}

fun parentOf(path: String): String = path.substringBeforeLast('/', "")
fun nameOf(path: String): String = path.substringAfterLast('/')
fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"
