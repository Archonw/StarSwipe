package io.github.archonw.starswipe.rating

import io.github.archonw.starswipe.db.AppDatabase
import io.github.archonw.starswipe.db.PhotoEntity
import io.github.archonw.starswipe.library.PhotoRules
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.Signature
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.xmp.Xmp
import io.github.archonw.starswipe.xmp.XmpRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

data class UndoToken(val pendingId: Long, val storageId: Long, val photoPath: String, val previousRating: Int?)
data class FlushResult(val written: Int, val retryLater: Boolean)

/** Bewertungen lokal annehmen und später in die Sidecars schreiben. */
class RatingService(
    private val db: AppDatabase,
    private val storages: suspend (Long) -> PhotoStorage?,
    private val enqueueWork: (Long) -> Unit,
) {
    private val pending = db.pending()
    private val photos = db.photos()
    private val flushLock = Mutex()

    companion object { const val MAX_ATTEMPTS = 5 }

    suspend fun rate(photo: PhotoEntity, stars: Int): UndoToken {
        require(stars in 1..5)
        val prev = photos.get(photo.storageId, photo.path)?.rating ?: photo.rating
        val id = pending.insert(PendingRatingEntity(storageId = photo.storageId, photoPath = photo.path,
            kind = PendingKind.RATE, rating = stars, previousRating = prev, createdAt = System.currentTimeMillis()))
        photos.updateRating(photo.storageId, photo.path, stars)
        enqueueWork(photo.storageId)
        return UndoToken(id, photo.storageId, photo.path, prev)
    }

    /**
     * Lokal sofort zurücksetzen; was mit der Datei passiert, wird unter dem Flush-Lock entschieden,
     * damit ein gerade laufender Schreibvorgang nicht „gewinnt“.
     */
    suspend fun undo(token: UndoToken) {
        photos.updateRating(token.storageId, token.photoPath, token.previousRating)
        val enqueue = flushLock.withLock {
            val p = pending.get(token.pendingId) ?: return@withLock false
            if (!p.done) { pending.delete(p.id); return@withLock false }
            if (p.writtenModified == null) return@withLock false // nie geschrieben (überholt oder verworfen)
            val beforeRating = p.beforeBytes?.let { Xmp.decodeStrict(it) }?.let { (Xmp.readRating(it) as? XmpRead.Ok)?.rating }
            val exact = if (p.beforeExisted) beforeRating == p.previousRating else p.previousRating == null
            val now = System.currentTimeMillis()
            if (exact) {
                pending.insert(p.copy(id = 0, kind = PendingKind.RESTORE, rating = p.previousRating, done = false, attempts = 0, createdAt = now))
            } else {
                // Dazwischen lag eine überholte Bewertung: deren Wert wieder schreiben
                pending.insert(PendingRatingEntity(storageId = p.storageId, photoPath = p.photoPath, kind = PendingKind.RATE,
                    rating = p.previousRating, previousRating = p.rating, createdAt = now))
            }
            true
        }
        if (enqueue) enqueueWork(token.storageId)
    }

    fun pendingCount(): Flow<Int> = pending.openCount()
    suspend fun pendingPaths(storageId: Long): Set<String> = pending.openPaths(storageId).toSet()

    suspend fun flush(): FlushResult = flushLock.withLock {
        var written = 0
        var retry = false
        val blocked = HashSet<Long>()
        val groups = pending.open().groupBy { it.storageId to it.photoPath }
        for ((_, ops) in groups) {
            val op = ops.last()
            if (op.storageId in blocked) continue
            val storage = storages(op.storageId)
            if (storage == null) {
                if (db.locations().get(op.storageId) == null) ops.forEach { pending.delete(it.id) }
                else { retry = true; blocked += op.storageId } // z. B. Zugangsdaten fehlen: Warteschlange behalten
                continue
            }
            ops.dropLast(1).forEach { pending.update(it.copy(done = true)) } // überholt
            try {
                if (process(storage, op)) written++
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException.Conflict) {
                retry = true
            } catch (e: StorageException) {
                retry = true; blocked += op.storageId
            } catch (e: Exception) {
                // Unerwarteter Fehler: dieses Foto später erneut versuchen, die anderen nicht aufhalten
                if (op.attempts + 1 >= MAX_ATTEMPTS) {
                    pending.update(op.copy(done = true, attempts = op.attempts + 1))
                    photos.markBroken(op.storageId, op.photoPath)
                } else {
                    pending.update(op.copy(attempts = op.attempts + 1))
                    retry = true
                }
            }
        }
        pending.prune()
        FlushResult(written, retry)
    }

    /** true, wenn geschrieben wurde. */
    private suspend fun process(storage: PhotoStorage, op: PendingRatingEntity): Boolean {
        if (storage.stat(op.photoPath) == null) {
            pending.update(op.copy(done = true))
            photos.delete(op.storageId, op.photoPath)
            return false
        }
        return when (op.kind) {
            PendingKind.RATE -> rate(storage, op)
            PendingKind.RESTORE -> restore(storage, op)
        }
    }

    private suspend fun rate(storage: PhotoStorage, op: PendingRatingEntity): Boolean {
        val photo = photos.get(op.storageId, op.photoPath)
        val path = photo?.sidecarPath ?: PhotoRules.sidecarPathFor(op.photoPath)
        repeat(2) { attempt ->
            val cur = storage.stat(path)
            val before = cur?.let { storage.read(path) }
            val data = if (before != null) {
                val text = Xmp.decodeStrict(before)
                val updated = text?.let { runCatching { Xmp.withRating(it, op.rating) }.getOrNull() }
                if (updated == null) {
                    pending.update(op.copy(done = true))
                    photos.updateSidecar(op.storageId, op.photoPath, path, cur.etag, cur.modified.toEpochMilli(), cur.size, broken = true)
                    return false
                }
                updated.toByteArray(Charsets.UTF_8)
            } else {
                if (op.rating == null) { pending.update(op.copy(done = true)); return false }
                Xmp.newSidecar(op.rating).toByteArray(Charsets.UTF_8)
            }
            try {
                val sig = storage.write(path, data, cur?.signature)
                pending.update(op.copy(done = true, sidecarPath = path, beforeBytes = before, beforeExisted = before != null,
                    writtenEtag = sig.etag, writtenModified = sig.modified.toEpochMilli(), writtenSize = sig.size))
                photos.updateSidecar(op.storageId, op.photoPath, path, sig.etag, sig.modified.toEpochMilli(), sig.size, broken = false)
                return true
            } catch (e: StorageException.Conflict) {
                if (attempt == 1) throw e
            }
        }
        return false
    }

    private suspend fun restore(storage: PhotoStorage, op: PendingRatingEntity): Boolean {
        val path = op.sidecarPath ?: run { pending.update(op.copy(done = true)); return false }
        val written = Signature(op.writtenEtag, Instant.ofEpochMilli(op.writtenModified!!), op.writtenSize ?: -1)
        val cur = storage.stat(path)
        if (cur != null && !written.matches(cur)) { pending.update(op.copy(done = true)); return false } // fremd geändert
        if (op.beforeExisted) {
            val sig = storage.write(path, op.beforeBytes!!, cur?.signature)
            photos.updateSidecar(op.storageId, op.photoPath, path, sig.etag, sig.modified.toEpochMilli(), sig.size, broken = false)
        } else {
            if (cur != null) storage.delete(path, cur.signature)
            photos.updateSidecar(op.storageId, op.photoPath, null, null, null, null, broken = false)
        }
        pending.update(op.copy(done = true, writtenModified = null))
        return true
    }
}
