package io.github.archonw.starswipe.ui.rate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PreviewCacheTest {
    private val dir = Files.createTempDirectory("vorschau").toFile()

    @Test fun storesAndReturnsBytes() {
        val c = PreviewCache(dir, maxBytes = 10_000)
        assertNull(c.get("smb://a.jpg:1"))
        c.put("smb://a.jpg:1", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), c.get("smb://a.jpg:1")!!.readBytes())
    }

    /** Geänderte Datei → anderer Schlüssel (enthält ETag/Änderungszeit) → kein veraltetes Bild. */
    @Test fun differentKeyMisses() {
        val c = PreviewCache(dir, maxBytes = 10_000)
        c.put("smb://a.jpg:1", byteArrayOf(1))
        assertNull(c.get("smb://a.jpg:2"))
    }

    @Test fun trimsOldestBeyondLimit() {
        val c = PreviewCache(dir, maxBytes = 2_500)
        c.put("a", ByteArray(1000)); dir.listFiles()!!.forEach { it.setLastModified(1_000) }
        c.put("b", ByteArray(1000)); c.get("b")!!.setLastModified(2_000)
        c.put("c", ByteArray(1000))
        assertNull(c.get("a"))
        assertTrue(c.get("b") != null && c.get("c") != null)
        assertEquals(2, dir.listFiles()!!.size)
    }
}
