package io.github.archonw.starswipe.library

import io.github.archonw.starswipe.db.location
import io.github.archonw.starswipe.db.testDb
import io.github.archonw.starswipe.storage.InMemoryStorage
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {
    private val db = testDb()
    @After fun close() = db.close()

    @Test fun refreshAllReportsFailuresPerLocation() = runBlocking {
        val ok = db.locations().insert(location(name = "Gut"))
        val bad = db.locations().insert(location(name = "Weg"))
        val off = db.locations().insert(location(name = "Aus", enabled = false))
        val stores = mapOf(
            ok to InMemoryStorage(mapOf("Album/a.jpg" to byteArrayOf(1))),
            bad to InMemoryStorage().apply { failWith = StorageException.Unreachable(java.io.IOException()) },
            off to InMemoryStorage(mapOf("Album/z.jpg" to byteArrayOf(1))),
        )
        val repo = LibraryRepository(db) { stores[it] }
        val failures = repo.refreshAll()
        assertEquals(setOf(bad), failures.keys)
        assertTrue(failures[bad] is StorageException.Unreachable)
        assertEquals(listOf("Album/a.jpg"), repo.queue(false).first().map { it.path })
    }

    @Test fun scansDoNotOverlap() = runBlocking {
        val id = db.locations().insert(location())
        val mem = InMemoryStorage(mapOf("Album/a.jpg" to byteArrayOf(1)))
        val now = AtomicInteger(0); var max = 0
        val slow = object : PhotoStorage by mem {
            override suspend fun list(dir: String): List<Entry> {
                val n = now.incrementAndGet(); max = maxOf(max, n); delay(50); now.decrementAndGet(); return mem.list(dir)
            }
        }
        val repo = LibraryRepository(db) { if (it == id) slow else null }
        listOf(async(Dispatchers.Default) { repo.refreshAll() }, async(Dispatchers.Default) { repo.refreshAll() }).awaitAll()
        assertEquals(1, max)
    }

    /** Gezählter, anhaltbarer Speicher: list() auf den Wurzelordner zählt als ein Einlesen. */
    private class Counting(files: Map<String, ByteArray>) {
        val mem = InMemoryStorage(files)
        val scans = AtomicInteger(0)
        @Volatile var gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val storage = object : PhotoStorage by mem {
            override suspend fun list(dir: String): List<Entry> { if (dir == "Album") scans.incrementAndGet(); gate.await(); return mem.list(dir) }
        }
    }

    /** Schlechtes Netz: Bewerten-Bildschirm mehrfach geöffnet → kein Stapel ganzer Einlese-Durchgänge, sondern Anschluss an den laufenden. */
    @Test fun requestsDuringRunningScanJoinIt() = runBlocking {
        val id = db.locations().insert(location())
        val c = Counting(mapOf("Album/a.jpg" to byteArrayOf(1)))
        val repo = LibraryRepository(db) { if (it == id) c.storage else null }
        val first = async(Dispatchers.Default) { repo.refreshAll() }
        repo.scanning.first { it != null }
        val found = AtomicInteger(0)
        val joiners = (1..3).map { async(Dispatchers.Default) { repo.refreshAll(onFound = { found.set(it) }) } }
        repo.active.first { it == 4 }
        c.gate.complete(Unit)
        (joiners + first).awaitAll()
        assertEquals(1, c.scans.get())
        assertEquals(1, found.get())
    }

    /** Geänderte Ordner während eines Einlesens: genau ein Folge-Durchgang, egal wie oft angefordert. */
    @Test fun rescanRequestsCoalesceIntoOneFollowUp() = runBlocking {
        val id = db.locations().insert(location())
        val c = Counting(mapOf("Album/a.jpg" to byteArrayOf(1)))
        val repo = LibraryRepository(db) { if (it == id) c.storage else null }
        val first = async(Dispatchers.Default) { repo.refreshAll() }
        repo.scanning.first { it != null }
        val more = (1..3).map { async(Dispatchers.Default) { repo.refreshAll(rescan = true) } }
        repo.active.first { it == 4 }
        c.gate.complete(Unit)
        (more + first).awaitAll()
        assertEquals(2, c.scans.get())
    }

    /** Wer nur einen Speicherort will, schließt sich nicht einem Einlesen eines anderen an. */
    @Test fun joinOnlyWhenRunningScanCoversRequest() = runBlocking {
        val a = db.locations().insert(location(name = "A"))
        val b = db.locations().insert(location(name = "B"))
        val ca = Counting(mapOf("Album/a.jpg" to byteArrayOf(1)))
        val cb = Counting(mapOf("Album/b.jpg" to byteArrayOf(1))).apply { gate.complete(Unit) }
        val repo = LibraryRepository(db) { mapOf(a to ca.storage, b to cb.storage)[it] }
        val first = async(Dispatchers.Default) { repo.refreshAll(only = a) }
        repo.scanning.first { it != null }
        val second = async(Dispatchers.Default) { repo.refreshAll(only = b) }
        repo.active.first { it == 2 }
        ca.gate.complete(Unit)
        listOf(first, second).awaitAll()
        assertEquals(1, ca.scans.get())
        assertEquals(1, cb.scans.get())
        assertEquals(1, db.photos().byStorage(b).size)
    }

    /** Bewerten erneut geöffnet kurz nach fertigem Einlesen: nicht gleich wieder alles einlesen. */
    @Test fun recentScanIsReusedWithinMaxAge() = runBlocking {
        val id = db.locations().insert(location())
        val c = Counting(mapOf("Album/a.jpg" to byteArrayOf(1))).apply { gate.complete(Unit) }
        var clock = 1_000_000L
        val repo = LibraryRepository(db) { if (it == id) c.storage else null }.also { it.clock = { clock } }
        repo.refreshAll(maxAgeMs = 60_000)
        clock += 59_000
        repo.refreshAll(maxAgeMs = 60_000)
        repo.refreshAll(only = id, maxAgeMs = 60_000)
        assertEquals(1, c.scans.get())
        clock += 2_000
        repo.refreshAll(maxAgeMs = 60_000)
        assertEquals(2, c.scans.get())
        repo.refreshAll() // ohne Höchstalter (Knopf „Neu einlesen“): immer
        assertEquals(3, c.scans.get())
    }

    /** Ein fehlgeschlagener Speicherort gilt nicht als frisch eingelesen. */
    @Test fun failedScanIsNotReused() = runBlocking {
        val id = db.locations().insert(location())
        val mem = InMemoryStorage(mapOf("Album/a.jpg" to byteArrayOf(1))).apply { failWith = StorageException.Unreachable(java.io.IOException()) }
        val repo = LibraryRepository(db) { if (it == id) mem else null }
        assertTrue(repo.refreshAll(maxAgeMs = 60_000).containsKey(id))
        mem.failWith = null
        assertEquals(emptyMap<Long, StorageException>(), repo.refreshAll(maxAgeMs = 60_000))
        assertEquals(1, db.photos().byStorage(id).size)
    }

    /** Solange ein Einlesen läuft oder wartet, ist [LibraryRepository.active] > 0 (hält den Vordergrund-Dienst am Leben). */
    @Test fun activeCountsRunningAndWaitingScans() = runBlocking {
        val id = db.locations().insert(location())
        val mem = InMemoryStorage(mapOf("Album/a.jpg" to byteArrayOf(1)))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seen = mutableListOf<Int>()
        lateinit var repo: LibraryRepository
        val slow = object : PhotoStorage by mem {
            override suspend fun list(dir: String): List<Entry> { gate.await(); return mem.list(dir) }
        }
        repo = LibraryRepository(db) { if (it == id) slow else null }
        assertEquals(0, repo.active.value)
        val jobs = listOf(async(Dispatchers.Default) { repo.refreshAll() }, async(Dispatchers.Default) { repo.refreshAll() })
        repo.active.first { it == 2 }
        seen += repo.active.value
        gate.complete(Unit)
        jobs.awaitAll()
        assertEquals(listOf(2), seen)
        assertEquals(0, repo.active.value)
    }

    /** Auch bei Fehler oder Abbruch fällt der Zähler zurück. */
    @Test fun activeResetsAfterFailure() = runBlocking {
        val id = db.locations().insert(location())
        val repo = LibraryRepository(db) { InMemoryStorage().apply { failWith = StorageException.Unreachable(java.io.IOException()) } }
        repo.refreshAll()
        assertEquals(0, repo.active.value)
    }

    /** Die Startseite zeigt, welcher Speicherort gerade eingelesen wird – danach nichts mehr. */
    @Test fun reportsWhichLocationIsBeingScanned() = runBlocking {
        val a = db.locations().insert(location(name = "A"))
        val b = db.locations().insert(location(name = "B"))
        val seen = mutableListOf<Long>()
        lateinit var repo: LibraryRepository
        fun spy(mem: InMemoryStorage) = object : PhotoStorage by mem {
            override suspend fun list(dir: String): List<Entry> { repo.scanning.value?.let { seen += it.storageId }; return mem.list(dir) }
        }
        val stores = mapOf(a to spy(InMemoryStorage(mapOf("Album/a.jpg" to byteArrayOf(1)))),
            b to spy(InMemoryStorage(mapOf("Album/b.jpg" to byteArrayOf(1), "Album/c.jpg" to byteArrayOf(1)))))
        repo = LibraryRepository(db) { stores[it] }
        assertEquals(null, repo.scanning.value)
        repo.refreshAll()
        assertEquals(listOf(a, b), seen.distinct())
        assertEquals(null, repo.scanning.value)
    }

    @Test fun scanStatusCountsFoundPhotosOfThatLocation() = runBlocking {
        val id = db.locations().insert(location())
        val repo = LibraryRepository(db) { InMemoryStorage(mapOf("Album/a.jpg" to byteArrayOf(1), "Album/b.jpg" to byteArrayOf(1))) }
        val states = mutableListOf<ScanStatus>()
        val job = launch(Dispatchers.Unconfined) { repo.scanning.collect { if (it != null) states += it } }
        repo.refreshAll()
        job.cancel()
        assertEquals(id, states.last().storageId)
        assertEquals(2, states.maxOf { it.found })
    }

    @Test fun scanRunsOffCallerThread() = runBlocking {
        val id = db.locations().insert(location())
        val mem = InMemoryStorage(mapOf("Album/a.jpg" to byteArrayOf(1)))
        var thread = ""
        val spy = object : PhotoStorage by mem {
            override suspend fun list(dir: String): List<Entry> { thread = Thread.currentThread().name; return mem.list(dir) }
        }
        val caller = Thread.currentThread().name
        LibraryRepository(db) { if (it == id) spy else null }.refreshAll()
        assertTrue("lief auf $thread", thread != caller)
    }

    @Test fun unexpectedErrorReportedPerLocation() = runBlocking {
        val id = db.locations().insert(location())
        val boom = object : PhotoStorage by InMemoryStorage() {
            override suspend fun list(dir: String): List<Entry> = throw IllegalStateException("kaputt")
        }
        val failures = LibraryRepository(db) { boom }.refreshAll()
        assertTrue(failures[id] is StorageException.Other)
    }

    @Test fun missingStorageInstanceReported() = runBlocking {
        val id = db.locations().insert(location(name = "Ohne Passwort"))
        val failures = LibraryRepository(db) { null }.refreshAll()
        assertTrue(failures[id] is StorageException.Unauthorized)
    }

    @Test fun locationWithoutFoldersIsReported() = runBlocking {
        val id = db.locations().insert(location(name = "Leer", folders = emptyList()))
        val repo = LibraryRepository(db) { InMemoryStorage(mapOf("Album/a.jpg" to byteArrayOf(1))) }
        val f = repo.refreshAll()[id]
        assertTrue("$f", f is StorageException.NoFolders)
    }

    @Test fun refreshOnlyOneLocation() = runBlocking {
        val a = db.locations().insert(location(name = "A"))
        val b = db.locations().insert(location(name = "B"))
        val reads = mutableListOf<Long>()
        val repo = LibraryRepository(db) { id -> reads += id; InMemoryStorage(mapOf("Album/x.jpg" to byteArrayOf(1))) }
        repo.refreshAll(only = b)
        assertEquals(listOf(b), reads)
        assertEquals(setOf(b), db.photos().byStorage(b).map { it.storageId }.toSet())
        assertEquals(0, db.photos().byStorage(a).size)
    }
}
