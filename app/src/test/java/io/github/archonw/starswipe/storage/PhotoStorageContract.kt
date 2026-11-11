package io.github.archonw.starswipe.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Gemeinsame Erwartungen an jede [PhotoStorage]-Implementierung. */
abstract class PhotoStorageContract {
    companion object {
        val TREE: Map<String, ByteArray> = mapOf(
            "Album/a.jpg" to "AAAA-bild-a".toByteArray(),
            "Album/Unter/b.jpg" to "BBBB-bild-b".toByteArray(),
            "Foto Upload/Bäume #1.jpg" to "CCCC-bild-c".toByteArray(),
        )
    }

    /** Liefert einen Speicher, der genau [files] enthält. */
    abstract fun create(files: Map<String, ByteArray>): PhotoStorage

    private val s by lazy { create(TREE) }

    @Test fun listsDirectChildrenOnly() = runBlocking {
        val names = s.list("Album").map { it.name to it.isDir }.toSet()
        assertEquals(setOf("a.jpg" to false, "Unter" to true), names)
        val root = s.list("").map { it.name }.toSet()
        assertEquals(setOf("Album", "Foto Upload"), root)
    }

    @Test fun listedPathsAreRelative() = runBlocking {
        assertEquals(listOf("Album/Unter/b.jpg"), s.list("Album/Unter").map { it.path })
    }

    @Test fun listsNamesWithSpacesAndUmlauts() = runBlocking {
        val e = s.list("Foto Upload").single()
        assertEquals("Bäume #1.jpg", e.name)
        assertEquals("Foto Upload/Bäume #1.jpg", e.path)
        assertEquals(11L, e.size)
    }

    @Test fun writeNewFailsIfExists() = runBlocking {
        try {
            s.write("Album/a.jpg", "x".toByteArray(), expected = null)
            fail("Conflict erwartet")
        } catch (_: StorageException.Conflict) {}
        assertArrayEquals(TREE["Album/a.jpg"], s.read("Album/a.jpg"))
    }

    @Test fun writeWithStaleSignatureConflicts() = runBlocking {
        val first = s.write("Album/a.jpg.xmp", "eins".toByteArray(), null)
        s.write("Album/a.jpg.xmp", "zwei-länger".toByteArray(), first)
        try {
            s.write("Album/a.jpg.xmp", "drei".toByteArray(), first)
            fail("Conflict erwartet")
        } catch (_: StorageException.Conflict) {}
        assertEquals("zwei-länger", String(s.read("Album/a.jpg.xmp")))
    }

    @Test fun writeThenReadRoundTrip() = runBlocking {
        val sig = s.write("Foto Upload/Bäume #1.jpg.xmp", "<x/>".toByteArray(), null)
        assertEquals("<x/>", String(s.read("Foto Upload/Bäume #1.jpg.xmp")))
        val st = s.stat("Foto Upload/Bäume #1.jpg.xmp")!!
        assertTrue(sig.matches(st))
        val sig2 = s.write("Foto Upload/Bäume #1.jpg.xmp", "<y/>".toByteArray(), sig)
        assertTrue(sig2.matches(s.stat("Foto Upload/Bäume #1.jpg.xmp")!!))
    }

    @Test fun deleteRemoves() = runBlocking {
        val sig = s.write("Album/a.jpg.xmp", "x".toByteArray(), null)
        s.delete("Album/a.jpg.xmp", sig)
        assertNull(s.stat("Album/a.jpg.xmp"))
        assertFalse(s.list("Album").any { it.name == "a.jpg.xmp" })
    }

    @Test fun statMissingIsNull() = runBlocking { assertNull(s.stat("Album/fehlt.jpg")) }

    @Test fun readMissingThrowsNotFound() = runBlocking {
        try { s.read("Album/fehlt.jpg"); fail() } catch (_: StorageException.NotFound) {}
    }

    @Test fun readWithMaxBytesTruncates() = runBlocking {
        assertEquals("AAAA", String(s.read("Album/a.jpg", maxBytes = 4)))
    }
}
