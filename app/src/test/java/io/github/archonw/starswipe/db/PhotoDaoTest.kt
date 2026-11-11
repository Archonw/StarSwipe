package io.github.archonw.starswipe.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotoDaoTest {
    private val db = testDb()
    private val dao = db.photos()
    @After fun close() = db.close()

    private fun paths(showRated: Boolean) = runBlocking { dao.queue(showRated, PhotoDao.ALL).first().map { it.path } }

    @Test fun queueSortsNewestFirstAcrossStorages() = runBlocking {
        val a = db.locations().insert(location(name = "A"))
        val b = db.locations().insert(location(name = "B"))
        dao.upsert(listOf(photo(a, "x/alt.jpg", 100), photo(b, "y/neu.jpg", 300), photo(a, "x/mitte.jpg", 200)))
        assertEquals(listOf("y/neu.jpg", "x/mitte.jpg", "x/alt.jpg"), paths(false))
    }

    @Test fun queueHidesRatedUnlessShowRated() = runBlocking {
        val a = db.locations().insert(location())
        dao.upsert(listOf(photo(a, "a.jpg", 1, rating = 4), photo(a, "b.jpg", 2), photo(a, "c.jpg", 3, broken = true)))
        assertEquals(listOf("b.jpg"), paths(false))
        assertEquals(listOf("b.jpg", "a.jpg"), paths(true))
    }

    @Test fun oneStarHiddenOnlyWhenFlagOff() = runBlocking {
        val off = db.locations().insert(location(oneStar = false))
        val on = db.locations().insert(location(oneStar = true))
        dao.upsert(listOf(photo(off, "off.jpg", 1, rating = 1), photo(on, "on.jpg", 2, rating = 1)))
        assertEquals(listOf("on.jpg"), paths(false))
    }

    @Test fun disabledStorageHidden() = runBlocking {
        val a = db.locations().insert(location(enabled = false))
        dao.upsert(listOf(photo(a, "a.jpg", 1)))
        assertEquals(emptyList<String>(), paths(true))
    }

    @Test fun foldersRoundTrip() = runBlocking {
        val id = db.locations().insert(location(folders = listOf("A/B", "Foto Upload")))
        assertEquals(listOf("A/B", "Foto Upload"), db.locations().get(id)!!.folders)
    }

    @Test fun queueCanBeLimitedToOneStorage() = runBlocking {
        val a = db.locations().insert(location(name = "A"))
        val b = db.locations().insert(location(name = "B"))
        dao.upsert(listOf(photo(a, "a.jpg", 1), photo(b, "b.jpg", 2)))
        assertEquals(listOf("a.jpg"), dao.queue(false, a).first().map { it.path })
        assertEquals(listOf("b.jpg", "a.jpg"), dao.queue(false, PhotoDao.ALL).first().map { it.path })
    }

    /** Startseite: unbewertete Fotos je Speicherort (mit „1 ★ als unbewertet“, ohne kaputte Sidecars). */
    @Test fun unratedCountsPerStorage() = runBlocking {
        val a = db.locations().insert(location(name = "A", oneStar = true))
        val b = db.locations().insert(location(name = "B"))
        dao.upsert(listOf(photo(a, "1.jpg", 1), photo(a, "2.jpg", 2, rating = 1), photo(a, "3.jpg", 3, rating = 4),
            photo(b, "4.jpg", 4, rating = 1), photo(b, "5.jpg", 5, broken = true)))
        assertEquals(mapOf(a to 2), dao.unratedCounts().first().associate { it.storageId to it.count })
    }
}
