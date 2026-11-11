package io.github.archonw.starswipe.library

import io.github.archonw.starswipe.db.location
import io.github.archonw.starswipe.db.testDb
import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.InMemoryStorage
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class ScannerTest {
    private val db = testDb()
    private val scanner = Scanner(db)
    @After fun close() = db.close()

    private fun res(p: String) = javaClass.getResource(p)!!.readBytes()
    private val jpg = res("/exif/sample.jpg")
    private val fstop = res("/xmp/fstop.xmp")

    private fun setup(files: Map<String, ByteArray>, oneStar: Boolean = false): Pair<Long, InMemoryStorage> = runBlocking {
        val id = db.locations().insert(location(folders = listOf("Album"), oneStar = oneStar))
        id to InMemoryStorage(files)
    }
    private fun scan(id: Long, s: InMemoryStorage) = runBlocking { scanner.scan(db.locations().get(id)!!, s) }
    private fun all(id: Long) = runBlocking { db.photos().byStorage(id).associateBy { it.path } }

    @Test fun scansRecursively() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/Unter/b.jpg" to jpg, "Anderes/c.jpg" to jpg))
        scan(id, s)
        assertEquals(setOf("Album/a.jpg", "Album/Unter/b.jpg"), all(id).keys)
        assertEquals("Album/Unter", all(id)["Album/Unter/b.jpg"]!!.folder)
    }

    @Test fun readsRatingFromSidecar() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to fstop))
        scan(id, s)
        val p = all(id)["Album/a.jpg"]!!
        assertEquals(4, p.rating)
        assertEquals("Album/a.jpg.xmp", p.sidecarPath)
    }

    @Test fun brokenSidecarMarked() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to "<kaputt".toByteArray()))
        scan(id, s)
        assertTrue(all(id)["Album/a.jpg"]!!.sidecarBroken)
    }

    @Test fun takenAtFromExif() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg))
        scan(id, s)
        val expected = LocalDateTime.of(2018, 3, 30, 16, 32, 44).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, all(id)["Album/a.jpg"]!!.takenAt)
    }

    @Test fun takenAtFallsBackToModified() {
        val (id, s) = setup(mapOf("Album/a.jpg" to "kein exif".toByteArray()))
        scan(id, s)
        assertEquals(runBlocking { s.stat("Album/a.jpg")!!.modified.toEpochMilli() }, all(id)["Album/a.jpg"]!!.takenAt)
    }

    @Test fun secondScanSkipsUnchangedSidecars() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to fstop))
        scan(id, s); s.reads.clear(); scan(id, s)
        assertEquals(emptyList<Any>(), s.reads)
    }

    @Test fun secondScanRereadsChangedSidecar() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to fstop))
        scan(id, s)
        s.put("Album/a.jpg.xmp", String(fstop).replace("xmp:Rating=\"4\"", "xmp:Rating=\"2\"").toByteArray())
        scan(id, s)
        assertEquals(2, all(id)["Album/a.jpg"]!!.rating)
    }

    @Test fun sidecarRemovedClearsRating() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to fstop))
        scan(id, s)
        runBlocking { s.delete("Album/a.jpg.xmp", null) }
        scan(id, s)
        assertNull(all(id)["Album/a.jpg"]!!.rating)
    }

    @Test fun brokenSidecarRecheckedOnNextScan() = runBlocking {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to fstop))
        scan(id, s)
        val p = db.photos().get(id, "Album/a.jpg")!!
        db.photos().upsert(listOf(p.copy(sidecarBroken = true, rating = null))) // z. B. alter Parser-Fehler
        scan(id, s)
        val again = db.photos().get(id, "Album/a.jpg")!!
        assertEquals(false, again.sidecarBroken)
        assertEquals(4, again.rating)
    }

    @Test fun removesVanishedPhotos() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/b.jpg" to jpg))
        scan(id, s)
        runBlocking { s.delete("Album/b.jpg", null) }
        scan(id, s)
        assertEquals(setOf("Album/a.jpg"), all(id).keys)
    }

    @Test fun failedScanKeepsCache() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg))
        scan(id, s)
        s.failWith = StorageException.Unreachable(java.io.IOException("weg"))
        try { scan(id, s); fail() } catch (_: StorageException.Unreachable) {}
        assertEquals(setOf("Album/a.jpg"), all(id).keys)
    }

    @Test fun skipsHiddenAndTrash() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/.trash/b.jpg" to jpg, "Album/@eaDir/c.jpg" to jpg, "Album/.d.jpg" to jpg))
        scan(id, s)
        assertEquals(setOf("Album/a.jpg"), all(id).keys)
    }

    @Test fun exifOnlyForNewPhotos() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg))
        scan(id, s); s.reads.clear(); scan(id, s)
        assertEquals(emptyList<Any>(), s.reads)
    }

    @Test fun nonUtf8SidecarMarkedBroken() {
        val latin1 = String(fstop).replace("fstop:favorite=\"0\"", "fstop:favorite=\"ä\"").toByteArray(Charsets.ISO_8859_1)
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to latin1))
        scan(id, s)
        assertTrue(all(id)["Album/a.jpg"]!!.sidecarBroken)
    }

    /** Speicher, der beim Auflisten von [dir] einmalig [action] ausführt (= etwas passiert während des Scans). */
    private fun during(m: InMemoryStorage, dir: String, action: suspend () -> Unit): PhotoStorage = object : PhotoStorage by m {
        var done = false
        override suspend fun list(d: String): List<Entry> { if (d == dir && !done) { done = true; action() }; return m.list(d) }
    }

    @Test fun ratingDuringScanSurvives() = runBlocking {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg))
        scan(id, s)
        val pending = mutableSetOf<String>()
        val st = during(s, "Album") { db.photos().updateRating(id, "Album/a.jpg", 5); pending += "Album/a.jpg" }
        scanner.scan(db.locations().get(id)!!, st, pendingPaths = { pending })
        assertEquals(5, all(id)["Album/a.jpg"]!!.rating)
    }

    @Test fun flushDuringScanIsNotOverwritten() = runBlocking {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg))
        scan(id, s)
        val st = during(s, "Album") {
            db.photos().updateRating(id, "Album/a.jpg", 4)
            db.photos().updateSidecar(id, "Album/a.jpg", "Album/a.jpg.xmp", "e99", 123, 10, false)
        }
        scanner.scan(db.locations().get(id)!!, st)
        val p = all(id)["Album/a.jpg"]!!
        assertEquals(4, p.rating)
        assertEquals("Album/a.jpg.xmp", p.sidecarPath)
    }

    @Test fun progressReportedPerDirectory() {
        val (id, s) = setup(mapOf("Album/a.jpg" to jpg, "Album/Unter/b.jpg" to jpg))
        var calls = 0
        runBlocking { scanner.scan(db.locations().get(id)!!, s, onBatch = { calls++ }) }
        assertTrue("calls=$calls", calls >= 2)
    }

    @Test fun takenAtFromFileNameSkipsExif() {
        val (id, s) = setup(mapOf("Album/PXL_20261108_131703921.jpg" to jpg))
        scan(id, s)
        val expected = LocalDateTime.of(2026, 11, 8, 13, 17, 3).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, all(id)["Album/PXL_20261108_131703921.jpg"]!!.takenAt)
        assertEquals(emptyList<Any>(), s.reads)
    }

    /** Ein großer Ordner darf nicht erst am Ende sichtbar werden. */
    @Test fun largeFolderSavedInChunks() = runBlocking {
        val files = (1..40).flatMap { listOf("Album/f$it.jpg" to jpg, "Album/f$it.jpg.xmp" to fstop) }.toMap()
        val (id, s) = setup(files)
        val seenAtFirstBatch = mutableListOf<Int>()
        scanner.scan(db.locations().get(id)!!, s, onBatch = { if (seenAtFirstBatch.isEmpty()) seenAtFirstBatch += db.photos().byStorage(id).size })
        assertTrue("erster Batch: $seenAtFirstBatch", seenAtFirstBatch.single() in 1..Scanner.CHUNK)
        assertEquals(40, all(id).size)
    }

    @Test fun readsInParallel() = runBlocking {
        val files = (1..20).associate { "Album/f$it.jpg" to jpg }
        val (id, m) = setup(files)
        var now = 0; var max = 0
        val slow = object : PhotoStorage by m {
            override suspend fun read(path: String, maxBytes: Long?): ByteArray {
                synchronized(this) { now++; max = maxOf(max, now) }
                kotlinx.coroutines.delay(20)
                synchronized(this) { now-- }
                return m.read(path, maxBytes)
            }
        }
        scanner.scan(db.locations().get(id)!!, slow)
        assertTrue("max=$max", max > 1)
        assertTrue("max=$max", max <= Scanner.PARALLEL)
    }

    @Test fun progressCountsPhotos() = runBlocking {
        val files = (1..30).associate { "Album/f$it.jpg" to jpg } + ("Album/Unter/x.jpg" to jpg)
        val (id, s) = setup(files)
        val seen = mutableListOf<Int>()
        scanner.scan(db.locations().get(id)!!, s, onProgress = { seen += it })
        assertEquals(31, seen.last())
        assertTrue(seen.size > 1)
    }

    /** Speicher, bei dem Lesezugriffe auf [path] die ersten [times] Male am Netz scheitern. */
    private fun flaky(m: InMemoryStorage, path: String, times: Int): PhotoStorage = object : PhotoStorage by m {
        var left = times
        override suspend fun read(p: String, maxBytes: Long?): ByteArray {
            if (p == path && left-- > 0) throw StorageException.Unreachable(java.io.IOException("stream was reset"))
            return m.read(p, maxBytes)
        }
    }

    @Test fun transientReadErrorIsRetried() = runBlocking {
        val (id, m) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to fstop, "Album/b.jpg" to jpg))
        scanner.scan(db.locations().get(id)!!, flaky(m, "Album/a.jpg.xmp", 1))
        assertEquals(4, all(id)["Album/a.jpg"]!!.rating)
        assertEquals(2, all(id).size)
    }

    /** Eine Datei, die dauerhaft nicht lesbar ist, darf den Rest des Speicherorts nicht verhindern. */
    @Test fun persistentFileErrorSkipsOnlyThatPhoto() = runBlocking {
        val (id, m) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to fstop, "Album/b.jpg" to jpg, "Album/c.jpg" to jpg))
        scanner.scan(db.locations().get(id)!!, flaky(m, "Album/a.jpg.xmp", 100))
        assertEquals(setOf("Album/b.jpg", "Album/c.jpg"), all(id).keys)
    }

    @Test fun persistentFileErrorKeepsCachedEntry() = runBlocking {
        val (id, m) = setup(mapOf("Album/a.jpg" to jpg, "Album/a.jpg.xmp" to fstop))
        scan(id, m)
        m.put("Album/a.jpg.xmp", fstop) // geändert → muss neu gelesen werden
        scanner.scan(db.locations().get(id)!!, flaky(m, "Album/a.jpg.xmp", 100))
        assertEquals(4, all(id)["Album/a.jpg"]!!.rating)
    }

    /** Rückmeldung schon nach dem Auflisten: gefundene Fotos, bevor das erste geprüft ist. */
    @Test fun reportsFoundBeforeChecking() = runBlocking {
        val files = (1..30).associate { "Album/f$it.jpg" to jpg } + ("Album/Unter/x.jpg" to jpg)
        val (id, s) = setup(files)
        val events = mutableListOf<String>()
        scanner.scan(db.locations().get(id)!!, s, onProgress = { events += "geprüft $it" }, onFound = { events += "gefunden $it" })
        assertEquals("gefunden 30", events.first())
        assertEquals("gefunden 31", events.last { it.startsWith("gefunden") })
    }
}
