package io.github.archonw.starswipe.rating

import io.github.archonw.starswipe.db.location
import io.github.archonw.starswipe.db.photo
import io.github.archonw.starswipe.db.testDb
import io.github.archonw.starswipe.library.Scanner
import io.github.archonw.starswipe.storage.InMemoryStorage
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.Signature
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.xmp.Xmp
import io.github.archonw.starswipe.xmp.XmpRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RatingServiceTest {
    private val db = testDb()
    @After fun close() = db.close()

    private val darktable = javaClass.getResource("/xmp/darktable.xmp")!!.readBytes()
    private var enqueued = 0

    private fun setup(files: Map<String, ByteArray>, wrap: (InMemoryStorage) -> PhotoStorage = { it }): Triple<Long, InMemoryStorage, RatingService> = runBlocking {
        val id = db.locations().insert(location(folders = listOf("Album")))
        val mem = InMemoryStorage(files)
        val s = wrap(mem)
        Scanner(db).scan(db.locations().get(id)!!, s)
        Triple(id, mem, RatingService(db, { if (it == id) s else null }) { enqueued++ })
    }
    private fun photoOf(id: Long, path: String) = runBlocking { db.photos().get(id, path)!! }
    private fun rating(mem: InMemoryStorage, path: String) = Xmp.readRating(String(mem.content(path)!!))

    @Test fun rateCreatesSidecarWhenMissing() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        svc.rate(photoOf(id, "Album/a.jpg"), 5)
        assertEquals(1, enqueued)
        assertEquals(FlushResult(1, false), svc.flush())
        assertEquals(XmpRead.Ok(5), rating(mem, "Album/a.jpg.xmp"))
        assertEquals("Album/a.jpg.xmp", photoOf(id, "Album/a.jpg").sidecarPath)
    }

    @Test fun rateReplacesDarktableZero() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1), "Album/a.jpg.xmp" to darktable))
        svc.rate(photoOf(id, "Album/a.jpg"), 5)
        svc.flush()
        assertEquals(String(darktable).replace("xmp:Rating=\"0\"", "xmp:Rating=\"5\""), String(mem.content("Album/a.jpg.xmp")!!))
    }

    @Test fun rateUpdatesLocalRatingImmediately() = runBlocking {
        val (id, _, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        svc.rate(photoOf(id, "Album/a.jpg"), 3)
        assertEquals(3, photoOf(id, "Album/a.jpg").rating)
        assertEquals(1, svc.pendingCount().first())
    }

    @Test fun latestPendingWins() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        svc.rate(photoOf(id, "Album/a.jpg"), 3)
        svc.rate(photoOf(id, "Album/a.jpg"), 5)
        svc.flush()
        assertEquals(listOf("Album/a.jpg.xmp"), mem.writes)
        assertEquals(XmpRead.Ok(5), rating(mem, "Album/a.jpg.xmp"))
        assertEquals(0, svc.pendingCount().first())
    }

    @Test fun conflictRetriesOnce() = runBlocking {
        var failNext = true
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1), "Album/a.jpg.xmp" to darktable)) { m ->
            object : PhotoStorage by m {
                override suspend fun write(path: String, data: ByteArray, expected: Signature?): Signature {
                    if (failNext) { failNext = false; throw StorageException.Conflict(path) }
                    return m.write(path, data, expected)
                }
            }
        }
        svc.rate(photoOf(id, "Album/a.jpg"), 4)
        assertEquals(FlushResult(1, false), svc.flush())
        assertEquals(XmpRead.Ok(4), rating(mem, "Album/a.jpg.xmp"))
    }

    @Test fun unreachableKeepsPending() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        svc.rate(photoOf(id, "Album/a.jpg"), 4)
        mem.failWith = StorageException.Unreachable(java.io.IOException())
        assertEquals(FlushResult(0, true), svc.flush())
        assertEquals(1, svc.pendingCount().first())
        mem.failWith = null
        assertEquals(FlushResult(1, false), svc.flush())
    }

    @Test fun photoDeletedDropsPending() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        svc.rate(photoOf(id, "Album/a.jpg"), 4)
        mem.delete("Album/a.jpg", null)
        assertEquals(FlushResult(0, false), svc.flush())
        assertEquals(0, svc.pendingCount().first())
        assertNull(db.photos().get(id, "Album/a.jpg"))
        assertNull(mem.content("Album/a.jpg.xmp"))
    }

    @Test fun brokenSidecarNotOverwritten() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        mem.put("Album/a.jpg.xmp", "<kaputt".toByteArray())
        svc.rate(photoOf(id, "Album/a.jpg"), 4)
        svc.flush()
        assertEquals("<kaputt", String(mem.content("Album/a.jpg.xmp")!!))
        assertTrue(photoOf(id, "Album/a.jpg").sidecarBroken)
        assertEquals(0, svc.pendingCount().first())
    }

    @Test fun undoBeforeFlushWritesNothing() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        val t = svc.rate(photoOf(id, "Album/a.jpg"), 4)
        svc.undo(t)
        svc.flush()
        assertNull(mem.content("Album/a.jpg.xmp"))
        assertNull(photoOf(id, "Album/a.jpg").rating)
    }

    @Test fun undoSecondRatingFallsBackToFirst() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        svc.rate(photoOf(id, "Album/a.jpg"), 3)
        val t = svc.rate(photoOf(id, "Album/a.jpg"), 5)
        svc.undo(t)
        assertEquals(3, photoOf(id, "Album/a.jpg").rating)
        svc.flush()
        assertEquals(XmpRead.Ok(3), rating(mem, "Album/a.jpg.xmp"))
    }

    @Test fun undoAfterFlushRestoresOriginalBytes() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1), "Album/a.jpg.xmp" to darktable))
        val t = svc.rate(photoOf(id, "Album/a.jpg"), 5)
        svc.flush()
        svc.undo(t)
        assertNull(photoOf(id, "Album/a.jpg").rating)
        svc.flush()
        assertArrayEquals(darktable, mem.content("Album/a.jpg.xmp"))
        assertEquals(0, svc.pendingCount().first())
    }

    @Test fun undoAfterFlushDeletesAppCreatedSidecar() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        val t = svc.rate(photoOf(id, "Album/a.jpg"), 5)
        svc.flush()
        svc.undo(t)
        svc.flush()
        assertFalse(mem.paths().contains("Album/a.jpg.xmp"))
        assertNull(photoOf(id, "Album/a.jpg").sidecarPath)
    }

    @Test fun pendingPathsListsUnwritten() = runBlocking {
        val (id, _, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1)))
        svc.rate(photoOf(id, "Album/a.jpg"), 2)
        assertEquals(setOf("Album/a.jpg"), svc.pendingPaths(id))
    }

    // --- Befunde aus dem Review ---

    @Test fun undoDuringWriteIsNotLost() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1))) { m ->
            object : PhotoStorage by m {
                override suspend fun write(path: String, data: ByteArray, expected: Signature?): Signature {
                    if (!writing.isCompleted) { writing.complete(Unit); release.await() }
                    return m.write(path, data, expected)
                }
            }
        }
        val t = svc.rate(photoOf(id, "Album/a.jpg"), 5)
        val flush = async(kotlinx.coroutines.Dispatchers.Default) { svc.flush() }
        writing.await()
        val undo = async(kotlinx.coroutines.Dispatchers.Default) { svc.undo(t) }
        repeat(20) { yield(); Thread.sleep(5) }
        assertNull(photoOf(id, "Album/a.jpg").rating) // lokal sofort zurückgesetzt
        release.complete(Unit)
        flush.await(); undo.await()
        svc.flush()
        assertNull(mem.content("Album/a.jpg.xmp"))
    }

    @Test fun undoAfterSupersededFlushKeepsEarlierRating() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1), "Album/a.jpg.xmp" to darktable))
        svc.rate(photoOf(id, "Album/a.jpg"), 3)
        val t = svc.rate(photoOf(id, "Album/a.jpg"), 4)
        svc.flush()
        svc.undo(t)
        assertEquals(3, photoOf(id, "Album/a.jpg").rating)
        svc.flush()
        assertEquals(XmpRead.Ok(3), rating(mem, "Album/a.jpg.xmp"))
    }

    @Test fun unexpectedErrorDoesNotBlockOtherPhotos() = runBlocking {
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1), "Album/b.jpg" to byteArrayOf(1))) { m ->
            object : PhotoStorage by m {
                override suspend fun write(path: String, data: ByteArray, expected: Signature?): Signature {
                    if (path == "Album/a.jpg.xmp") throw IllegalStateException("kaputt")
                    return m.write(path, data, expected)
                }
            }
        }
        svc.rate(photoOf(id, "Album/a.jpg"), 2)
        svc.rate(photoOf(id, "Album/b.jpg"), 3)
        val r = svc.flush()
        assertEquals(1, r.written)
        assertTrue(r.retryLater)
        assertEquals(XmpRead.Ok(3), rating(mem, "Album/b.jpg.xmp"))
        repeat(RatingService.MAX_ATTEMPTS) { svc.flush() }
        assertEquals(0, svc.pendingCount().first()) // nach Höchstzahl aufgegeben
        assertTrue(photoOf(id, "Album/a.jpg").sidecarBroken)
    }

    @Test fun missingStorageInstanceKeepsQueue() = runBlocking {
        val id = db.locations().insert(location(folders = listOf("Album")))
        db.photos().upsert(listOf(photo(id, "Album/a.jpg", 1)))
        val svc = RatingService(db, { null }) {}
        svc.rate(photoOf(id, "Album/a.jpg"), 4)
        assertEquals(FlushResult(0, true), svc.flush())
        assertEquals(1, svc.pendingCount().first())
    }

    @Test fun deletedLocationDropsQueue() = runBlocking {
        val id = db.locations().insert(location(folders = listOf("Album")))
        db.photos().upsert(listOf(photo(id, "Album/a.jpg", 1)))
        val svc = RatingService(db, { null }) {}
        svc.rate(photoOf(id, "Album/a.jpg"), 4)
        db.locations().delete(id)
        assertEquals(FlushResult(0, false), svc.flush())
        assertEquals(0, svc.pendingCount().first())
    }

    @Test fun nonUtf8SidecarNotRewritten() = runBlocking {
        val latin1 = String(darktable).replace("xmpMM:DerivedFrom=\"IMAG1158.jpg\"", "xmpMM:DerivedFrom=\"Bäume.jpg\"").toByteArray(Charsets.ISO_8859_1)
        val (id, mem, svc) = setup(mapOf("Album/a.jpg" to byteArrayOf(1), "Album/a.jpg.xmp" to latin1))
        svc.rate(photoOf(id, "Album/a.jpg"), 4)
        svc.flush()
        assertArrayEquals(latin1, mem.content("Album/a.jpg.xmp"))
        assertTrue(photoOf(id, "Album/a.jpg").sidecarBroken)
    }
}
