package io.github.archonw.starswipe.ui.rate

import io.github.archonw.starswipe.db.AppDatabase
import io.github.archonw.starswipe.db.location
import io.github.archonw.starswipe.db.testDb
import io.github.archonw.starswipe.library.LibraryRepository
import io.github.archonw.starswipe.rating.RatingService
import io.github.archonw.starswipe.storage.InMemoryStorage
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.storage.Entry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "de")
class RateCoreTest {
    private fun RateCore.msg(): String? = state.value.message?.resolve(
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources)

    private val db: AppDatabase = testDb()
    @After fun close() = db.close()
    private val stores = mutableMapOf<Long, PhotoStorage>()

    private fun core(vararg names: String): RateCore = runBlocking {
        val id = db.locations().insert(location(name = "NAS", folders = listOf("Album")))
        // Dateinamen absteigend sortiert = neueste zuerst über Änderungszeit
        stores[id] = InMemoryStorage(names.associate { "Album/$it" to "x".toByteArray() })
        val lib = LibraryRepository(db) { stores[it] }
        val ratings = RatingService(db, { stores[it] }) {}
        RateCore(lib, ratings) { db.locations().get(it)?.name }.also { it.refresh() }
    }

    /** Alle Pfade der Warteschlange in Anzeige-Reihenfolge (durchblättern und zurück). */
    private suspend fun RateCore.order(): List<String> {
        val out = mutableListOf<String>()
        while (state.value.current != null) { out += state.value.current!!.path; skip() }
        repeat(out.size) { back() }
        return out
    }

    @Test fun foldersListedInQueueOrderWithCounts() = runTest {
        val c = core("Mai/a.jpg", "Mai/b.jpg", "Juni/c.jpg", "Juni/Urlaub/d.jpg", "Juni/Urlaub/e.jpg", "Juni/Urlaub/f.jpg")
        val order = c.order()
        val f = c.folders()
        assertEquals(order.map { it.substringBeforeLast('/') }.distinct(), f.map { it.folder })
        assertEquals(mapOf("Album/Mai" to 2, "Album/Juni" to 1, "Album/Juni/Urlaub" to 3), f.associate { it.folder to it.count })
        assertEquals(order.first().substringBeforeLast('/'), f.single { it.current }.folder)
        // Gemeinsamer Anfang nur „Album“: ein Teil bleibt zur Orientierung, also nichts gekürzt
        assertEquals(mapOf("Album/Mai" to "Album/Mai", "Album/Juni" to "Album/Juni", "Album/Juni/Urlaub" to "Album/Juni/Urlaub"),
            f.associate { it.folder to it.label })
    }

    @Test fun longCommonPathIsShortened() = runTest {
        val id = db.locations().insert(location(name = "NAS", folders = listOf("Bilder")))
        stores[id] = InMemoryStorage(mapOf("Bilder/Alle/Jan/2026/11/a.jpg" to byteArrayOf(1), "Bilder/Alle/Jan/2026/09/Urlaub/b.jpg" to byteArrayOf(1)))
        val c = RateCore(LibraryRepository(db) { stores[it] }, RatingService(db, { stores[it] }) {}) { db.locations().get(it)?.name }
        c.refresh()
        assertEquals(setOf("…/2026/11", "…/2026/09/Urlaub"), c.folders().map { it.label }.toSet())
    }

    @Test fun jumpGoesToFirstUnratedOfFolderAndKeepsUndo() = runTest {
        val c = core("Mai/a.jpg", "Mai/b.jpg", "Juni/c.jpg", "Juni/Urlaub/d.jpg", "Juni/Urlaub/e.jpg")
        val order = c.order()
        val target = order.last().substringBeforeLast('/')
        val firstOfTarget = order.first { it.startsWith("$target/") }
        c.rate(3)
        c.jumpTo(c.folders().single { it.folder == target })
        assertEquals(firstOfTarget, c.state.value.current!!.path)
        assertTrue(c.state.value.canGoBack)
        assertEquals(target, c.folders().single { it.current }.folder)
        c.undo()
        assertEquals(order.first(), c.state.value.current!!.path)
    }

    @Test fun jumpSkipsPhotosRatedInThisSession() = runTest {
        val c = core("Mai/a.jpg", "Mai/b.jpg", "Juni/c.jpg")
        val order = c.order()
        val first = order.first().substringBeforeLast('/')
        c.rate(4) // erstes Foto des ersten Ordners bewertet
        c.jumpTo(c.folders().last())
        c.jumpTo(c.folders().single { it.folder == first })
        val expected = order.drop(1).firstOrNull { it.startsWith("$first/") } ?: order.first()
        assertEquals(expected, c.state.value.current!!.path)
    }

    @Test fun emptyQueueHasNullCurrent() = runTest {
        val c = core()
        assertNull(c.state.value.current)
        assertEquals(0, c.state.value.remaining)
    }

    @Test fun rateAdvancesAndDecrementsRemaining() = runTest {
        val c = core("a.jpg", "b.jpg", "c.jpg")
        assertEquals("Album/c.jpg", c.state.value.current!!.path)
        assertEquals(3, c.state.value.remaining)
        assertEquals("NAS", c.state.value.storageName)
        c.rate(4)
        assertEquals("Album/b.jpg", c.state.value.current!!.path)
        assertEquals(2, c.state.value.remaining)
        assertEquals(4, db.photos().get(c.lastRated!!.storageId, "Album/c.jpg")!!.rating)
        assertTrue(c.state.value.canUndo)
    }

    @Test fun skipAdvancesWithoutRating() = runTest {
        val c = core("a.jpg", "b.jpg")
        c.skip()
        assertEquals("Album/a.jpg", c.state.value.current!!.path)
        assertFalse(c.state.value.canUndo)
        assertNull(db.photos().byStorage(1).first { it.path == "Album/b.jpg" }.rating)
    }

    @Test fun backReturnsToPrevious() = runTest {
        val c = core("a.jpg", "b.jpg")
        assertFalse(c.state.value.canGoBack)
        c.skip(); c.back()
        assertEquals("Album/b.jpg", c.state.value.current!!.path)
    }

    @Test fun undoRestoresPreviousPhotoAsCurrent() = runTest {
        val c = core("a.jpg", "b.jpg", "c.jpg")
        c.rate(5); c.skip(); c.undo()
        assertEquals("Album/c.jpg", c.state.value.current!!.path)
        assertNull(db.photos().byStorage(1).first { it.path == "Album/c.jpg" }.rating)
        assertFalse(c.state.value.canUndo)
    }

    @Test fun showRatedIncludesRated() = runTest {
        val c = core("a.jpg", "b.jpg")
        c.rate(3)
        c.setShowRated(true)
        assertEquals(2, c.state.value.remaining)
        assertEquals("Album/b.jpg", c.state.value.current!!.path)
        assertEquals(3, c.state.value.current!!.rating)
        c.setShowRated(false)
        assertEquals("Album/a.jpg", c.state.value.current!!.path)
    }

    @Test fun unreachableStorageSkippedWithMessage() = runTest {
        val c = core("a.jpg")
        val id2 = db.locations().insert(location(name = "Cloud", folders = listOf("Album")))
        stores[id2] = InMemoryStorage(mapOf("Album/z.jpg" to "x".toByteArray()))
        c.refresh()
        assertEquals(2, c.state.value.remaining)
        (stores[id2] as InMemoryStorage).failWith = StorageException.Unreachable(java.io.IOException())
        c.refresh()
        assertEquals(1, c.state.value.remaining)
        assertEquals("Album/a.jpg", c.state.value.current!!.path)
        assertTrue(c.msg()!!.contains("Cloud"))
        assertTrue(c.msg()!!, c.msg()!!.contains("IOException"))
    }

    @Test fun firstScanShowsPhotosBeforeScanEnds() = runBlocking {
        val id = db.locations().insert(location(name = "NAS", folders = listOf("Album")))
        val gate = CompletableDeferred<Unit>()
        val mem = InMemoryStorage(mapOf("Album/a.jpg" to "x".toByteArray(), "Album/Unter/b.jpg" to "x".toByteArray()))
        stores[id] = object : PhotoStorage by mem {
            override suspend fun list(dir: String): List<Entry> { if (dir == "Album/Unter") gate.await(); return mem.list(dir) }
        }
        val lib = LibraryRepository(db) { stores[it] }
        val c = RateCore(lib, RatingService(db, { stores[it] }) {}) { db.locations().get(it)?.name }
        val job = async(Dispatchers.Default) { c.refresh() }
        var waited = 0
        while (c.state.value.current == null && waited < 200) { Thread.sleep(10); waited++ }
        assertEquals("Album/a.jpg", c.state.value.current?.path)
        assertTrue(c.state.value.loading)
        gate.complete(Unit); job.await()
        assertEquals(2, c.state.value.remaining)
    }

    @Test fun doubleTapRatesTwoDifferentPhotos() = runBlocking {
        val c = core("a.jpg", "b.jpg", "c.jpg")
        listOf(async(Dispatchers.Default) { c.rate(4) }, async(Dispatchers.Default) { c.rate(2) }).forEach { it.await() }
        val rated = db.photos().byStorage(1).filter { it.rating != null }.map { it.path }.toSet()
        assertEquals(setOf("Album/c.jpg", "Album/b.jpg"), rated)
        assertEquals("Album/a.jpg", c.state.value.current!!.path)
    }

    @Test fun missingCredentialsMessage() = runTest {
        val c = core("a.jpg")
        stores.clear()
        c.refresh()
        assertTrue(c.msg()!!.contains("Zugangsdaten"))
    }

    @Test @org.robolectric.annotation.Config(qualifiers = "en") fun missingCredentialsMessageInEnglish() = runTest {
        val c = core("a.jpg")
        stores.clear()
        c.refresh()
        assertTrue(c.msg()!!, c.msg()!!.contains("sign-in failed"))
    }

    @Test @org.robolectric.annotation.Config(qualifiers = "es") fun missingCredentialsMessageInSpanish() = runTest {
        val c = core("a.jpg")
        stores.clear()
        c.refresh()
        assertTrue(c.msg()!!, c.msg()!!.contains("datos de acceso"))
    }

    @Test fun photosAppearWhenScannedElsewhereWhileEmpty() = runBlocking {
        val c = core() // leer
        val watcher = async(Dispatchers.Default) { c.watch() }
        val id2 = db.locations().insert(location(name = "Neu", folders = listOf("Album")))
        stores[id2] = InMemoryStorage(mapOf("Album/n.jpg" to "x".toByteArray()))
        LibraryRepository(db) { stores[it] }.refreshAll() // z. B. nach dem Anlegen eines Speicherorts
        var waited = 0
        while (c.state.value.current == null && waited < 200) { Thread.sleep(10); waited++ }
        watcher.cancel()
        assertEquals("Album/n.jpg", c.state.value.current?.path)
    }

    /** Fortschrittsanzeige: Zahl der geprüften Fotos wird während des Einlesens hochgezählt. */
    @Test fun refreshReportsScannedCount() = runTest {
        val c = core("a.jpg", "b.jpg", "c.jpg")
        assertEquals(3, c.state.value.scanned)
        assertEquals(3, c.state.value.found)
        assertFalse(c.state.value.loading)
    }

    /** Speicherort entfernt, während schon geblättert wurde: seine Fotos verschwinden aus dem Stapel. */
    @Test fun removedLocationDisappearsAfterBrowsing() = runTest {
        val c = core("a.jpg", "b.jpg")
        c.skip()
        assertTrue(c.state.value.canGoBack)
        db.photos().deleteByStorage(1); db.locations().delete(1)
        c.sync()
        assertNull(c.state.value.current)
        assertEquals(0, c.state.value.remaining)
    }

    /** Neuer Speicherort, während schon geblättert wurde: seine Fotos werden hinten angehängt. */
    @Test fun newLocationAppearsAfterBrowsing() = runTest {
        val c = core("a.jpg", "b.jpg")
        c.skip()
        val id2 = db.locations().insert(location(name = "Handy", folders = listOf("Album")))
        stores[id2] = InMemoryStorage(mapOf("Album/z.jpg" to "x".toByteArray()))
        c.refresh()
        assertEquals("Album/a.jpg", c.state.value.current!!.path)
        assertEquals(2, c.state.value.remaining)
    }

    /** Bewertete Fotos bleiben beim Abgleich im Stapel (Zurück muss sie weiter zeigen). */
    @Test fun syncKeepsRatedPhotos() = runTest {
        val c = core("a.jpg", "b.jpg")
        c.rate(3)
        c.sync()
        c.back()
        assertEquals("Album/b.jpg", c.state.value.current!!.path)
    }

    @Test fun onlyOneStorageIsRated() = runTest {
        val a = db.locations().insert(location(name = "A", folders = listOf("Album")))
        val b = db.locations().insert(location(name = "B", folders = listOf("Album")))
        stores[a] = InMemoryStorage(mapOf("Album/a.jpg" to "x".toByteArray()))
        stores[b] = InMemoryStorage(mapOf("Album/b.jpg" to "x".toByteArray()))
        val lib = LibraryRepository(db) { stores[it] }
        val c = RateCore(lib, RatingService(db, { stores[it] }) {}, only = b) { db.locations().get(it)?.name }
        c.refresh()
        assertEquals(listOf("Album/b.jpg"), listOfNotNull(c.state.value.current?.path) + c.state.value.upcoming.map { it.path })
        assertEquals(1, c.state.value.remaining)
    }

    /** Vorladen: die nächsten 5 Fotos. */
    @Test fun upcomingHoldsFive() = runTest {
        val c = core("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg", "f.jpg", "g.jpg")
        assertEquals(5, c.state.value.upcoming.size)
    }
}
