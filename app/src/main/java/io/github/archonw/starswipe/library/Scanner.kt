package io.github.archonw.starswipe.library

import io.github.archonw.starswipe.db.AppDatabase
import io.github.archonw.starswipe.db.PhotoEntity
import io.github.archonw.starswipe.db.StorageLocationEntity
import io.github.archonw.starswipe.exif.ExifDate
import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.Signature
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.xmp.Xmp
import io.github.archonw.starswipe.xmp.XmpRead
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneId

/** Liest die gewählten Ordner eines Speicherorts und gleicht den Cache ab. */
class Scanner(private val db: AppDatabase) {
    companion object {
        const val CHUNK = 12
        const val PARALLEL = 4
        const val RETRIES = 2
        const val RETRY_DELAY_MS = 300L
    }

    /** Kurze Netzaussetzer (Verbindung zurückgesetzt o. Ä.) abfangen: bis zu [RETRIES] Wiederholungen. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try { return block() } catch (e: StorageException.Unreachable) {
                if (++attempt > RETRIES) throw e
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }
    private var lastRun = 0L

    /**
     * [pendingPaths]: Fotos mit noch nicht geschriebener Bewertung (wird je Ordner neu abgefragt).
     * [onBatch]: nach jedem gespeicherten Ordner, damit die Oberfläche früh etwas zeigen kann.
     */
    suspend fun scan(
        loc: StorageLocationEntity,
        storage: PhotoStorage,
        pendingPaths: suspend () -> Set<String> = { emptySet() },
        onBatch: suspend () -> Unit = {},
        onProgress: suspend (Int) -> Unit = {},
        onFound: suspend (Int) -> Unit = {},
    ) {
        val run = synchronized(this) { maxOf(System.currentTimeMillis(), lastRun + 1).also { lastRun = it } }
        val old = db.photos().byStorage(loc.id).associateBy { it.path }
        val visited = HashSet<String>()

        val gate = Semaphore(PARALLEL)
        var checked = 0
        var found = 0

        suspend fun walk(dir: String) {
            if (!visited.add(dir)) return
            val entries = retrying { storage.list(dir) }.filterNot(PhotoRules::skip)
            // Neueste (nach Dateiname) zuerst, in kleinen Häppchen speichern, damit früh etwas zu sehen ist.
            val pairs = PhotoRules.pairSidecars(entries).toList().sortedByDescending { it.first.name }
            if (pairs.isNotEmpty()) { found += pairs.size; onFound(found) }
            for (chunk in pairs.chunked(CHUNK)) {
                val batch = coroutineScope {
                    chunk.map { (photo, sidecar) ->
                        async {
                            gate.withPermit {
                                // Einzelne Datei nicht lesbar: überspringen (bzw. alten Stand behalten), Rest weiter einlesen.
                                try { retrying { entity(loc.id, photo, sidecar, old[photo.path], storage, run) } }
                                catch (e: StorageException.Unreachable) { old[photo.path]?.copy(seen = run) }
                                catch (e: StorageException.NotFound) { null }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                save(loc.id, dir, batch, old, pendingPaths())
                checked += batch.size
                onBatch()
                onProgress(checked)
            }
            for (d in entries.filter { it.isDir }) walk(d.path)
        }

        for (folder in loc.folders) walk(folder.trim('/'))
        db.photos().deleteNotSeen(loc.id, run)
    }

    /**
     * Speichert einen Ordner. Hat sich ein Eintrag seit Scan-Beginn in der Datenbank geändert
     * (Bewertung oder Schreibvorgang während des Scans) oder wartet eine Bewertung, gewinnt die Datenbank.
     */
    private suspend fun save(storageId: Long, folder: String, batch: List<PhotoEntity>, old: Map<String, PhotoEntity>, pending: Set<String>) {
        val current = db.photos().byFolder(storageId, folder).associateBy { it.path }
        db.photos().upsert(batch.map { e ->
            val cur = current[e.path] ?: return@map e
            val o = old[e.path]
            val changedDuringScan = o == null || cur.rating != o.rating || cur.sidecarPath != o.sidecarPath ||
                cur.sidecarEtag != o.sidecarEtag || cur.sidecarModified != o.sidecarModified
            when {
                changedDuringScan -> e.copy(rating = cur.rating, sidecarPath = cur.sidecarPath, sidecarEtag = cur.sidecarEtag,
                    sidecarModified = cur.sidecarModified, sidecarSize = cur.sidecarSize, sidecarBroken = cur.sidecarBroken)
                e.path in pending -> e.copy(rating = cur.rating)
                else -> e
            }
        })
    }

    private suspend fun entity(storageId: Long, e: Entry, sc: Entry?, old: PhotoEntity?, storage: PhotoStorage, run: Long): PhotoEntity {
        val takenAt = e.takenAt?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: old?.takenAt
            ?: FileDates.fromName(e.name)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: readExif(storage, e)
            ?: e.modified.toEpochMilli()

        var rating: Int? = null
        var broken = false
        if (sc != null) {
            val oldSig = old?.sidecarModified?.let { Signature(old.sidecarEtag, Instant.ofEpochMilli(it), old.sidecarSize ?: -1) }
            if (old != null && !old.sidecarBroken && old.sidecarPath == sc.path && oldSig != null && oldSig.matches(sc)) {
                rating = old.rating; broken = old.sidecarBroken
            } else {
                val text = Xmp.decodeStrict(storage.read(sc.path))
                when (val r = text?.let { Xmp.readRating(it) } ?: XmpRead.Broken) {
                    is XmpRead.Ok -> rating = r.rating
                    XmpRead.Broken -> broken = true
                }
            }
        }
        return PhotoEntity(
            storageId, e.path, e.name, e.path.substringBeforeLast('/', ""), takenAt,
            e.etag, e.modified.toEpochMilli(), e.size, e.fileId,
            sc?.path, sc?.etag, sc?.modified?.toEpochMilli(), sc?.size,
            rating, broken, run,
        )
    }

    private suspend fun readExif(storage: PhotoStorage, e: Entry): Long? = try {
        ExifDate.read(storage.read(e.path, ExifDate.HEAD_BYTES.toLong()))
            ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    } catch (ex: StorageException.NotFound) {
        null
    }
}
