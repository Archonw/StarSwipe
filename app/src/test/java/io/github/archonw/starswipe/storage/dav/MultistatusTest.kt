package io.github.archonw.starswipe.storage.dav

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class MultistatusTest {
    private val body = javaClass.getResource("/dav/propfind.xml")!!.readText()
    private val root = "https://cloud.example.de/remote.php/dav/files/jan/".toHttpUrl()
    private val entries = Multistatus.parse(body, root, "Foto Upload")

    @Test fun selfOmitted() = assertEquals(3, entries.size)

    @Test fun dirParsed() {
        val d = entries.first { it.name == "Unter" }
        assertTrue(d.isDir)
        assertEquals("Foto Upload/Unter", d.path)
    }

    @Test fun fileParsed() {
        val f = entries.first { it.name == "Bäume #1.jpg" }
        assertEquals("Foto Upload/Bäume #1.jpg", f.path)
        assertEquals(2867311L, f.size)
        assertEquals("abc123", f.etag)
        assertEquals("image/jpeg", f.contentType)
        assertEquals(4711L, f.fileId)
        assertEquals(Instant.parse("2018-03-30T14:32:44Z"), f.modified)
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochSecond(1522420364), ZoneId.systemDefault()), f.takenAt)
    }

    @Test fun missingPropsAreNull() {
        val x = entries.first { it.name.endsWith(".xmp") }
        assertNull(x.takenAt)
    }
}
