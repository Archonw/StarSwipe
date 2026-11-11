package io.github.archonw.starswipe.library

import io.github.archonw.starswipe.storage.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PhotoRulesTest {
    private fun e(name: String, dir: Boolean = false) = Entry("A/$name", name, dir, 1, Instant.EPOCH, null, null, null, null)

    private fun pairs(vararg names: String) =
        PhotoRules.pairSidecars(names.map { e(it) }).map { (k, v) -> k.name to v?.name }.toMap()

    @Test fun pairsJpgXmp() = assertEquals(mapOf("a.jpg" to "a.jpg.xmp"), pairs("a.jpg", "a.jpg.xmp"))
    @Test fun pairsUpperCase() = assertEquals(mapOf("IMG.JPG" to "img.jpg.xmp"), pairs("IMG.JPG", "img.jpg.xmp"))
    @Test fun prefersFullNameSidecar() = assertEquals(mapOf("a.jpg" to "a.jpg.xmp"), pairs("a.xmp", "a.jpg", "a.jpg.xmp"))
    @Test fun bareXmpFallback() = assertEquals(mapOf("a.jpg" to "a.xmp"), pairs("a.jpg", "a.xmp"))
    @Test fun noSidecar() = assertEquals(mapOf("a.jpg" to null), pairs("a.jpg"))
    @Test fun xmpIsNotImage() = assertFalse(PhotoRules.isImage(e("a.jpg.xmp")))
    @Test fun videoIsNotImage() = assertFalse(PhotoRules.isImage(e("a.mp4")))
    @Test fun dirIsNotImage() = assertFalse(PhotoRules.isImage(e("x.jpg", dir = true)))
    @Test fun heicAndRawAreImages() = listOf("a.HEIC", "b.dng", "c.CR2", "d.webp").forEach { assertTrue(it, PhotoRules.isImage(e(it))) }
    @Test fun contentTypeImageCounts() = assertTrue(PhotoRules.isImage(e("ohne-endung").copy(contentType = "image/jpeg")))
    @Test fun sidecarPathKeepsCase() = assertEquals("A/IMG.JPG.xmp", PhotoRules.sidecarPathFor("A/IMG.JPG"))
    @Test fun skipsHidden() {
        assertTrue(PhotoRules.skip(e(".nomedia"))); assertTrue(PhotoRules.skip(e(".trash", true)))
        assertTrue(PhotoRules.skip(e("@eaDir", true))); assertTrue(PhotoRules.skip(e("#recycle", true)))
        assertFalse(PhotoRules.skip(e("Urlaub", true)))
    }
    @Test fun unratedRules() {
        assertTrue(PhotoRules.isUnrated(null, false)); assertTrue(PhotoRules.isUnrated(1, true))
        assertFalse(PhotoRules.isUnrated(1, false)); assertFalse(PhotoRules.isUnrated(5, true))
    }
}
