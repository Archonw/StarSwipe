package io.github.archonw.starswipe.xmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpTest {
    private fun fixture(name: String) =
        javaClass.getResource("/xmp/$name")!!.readText(Charsets.UTF_8)

    private val darktable = fixture("darktable.xmp")
    private val fstop = fixture("fstop.xmp")
    private val element = fixture("element.xmp")
    private val digikam = fixture("digikam.xmp")
    private val broken = fixture("broken.xmp")

    @Test fun readsDarktableZeroAsNull() = assertEquals(XmpRead.Ok(null), Xmp.readRating(darktable))
    @Test fun readsAttribute() = assertEquals(XmpRead.Ok(4), Xmp.readRating(fstop))
    @Test fun readsElement() = assertEquals(XmpRead.Ok(3), Xmp.readRating(element))
    @Test fun missingRating() = assertEquals(XmpRead.Ok(null), Xmp.readRating(digikam))
    @Test fun rejectedMinusOneIsNull() =
        assertEquals(XmpRead.Ok(null), Xmp.readRating(fstop.replace("xmp:Rating=\"4\"", "xmp:Rating=\"-1\"")))
    @Test fun brokenXml() = assertEquals(XmpRead.Broken, Xmp.readRating(broken))

    @Test fun replaceKeepsEverythingElse() {
        val expected = darktable.replace("xmp:Rating=\"0\"", "xmp:Rating=\"5\"")
        assertTrue(expected != darktable)
        assertEquals(expected, Xmp.withRating(darktable, 5))
    }

    @Test fun replaceElement() {
        val out = Xmp.withRating(element, 2)
        assertEquals(XmpRead.Ok(2), Xmp.readRating(out))
        assertTrue(out.contains("<xmp:Rating>2</xmp:Rating>"))
    }

    @Test fun insertIntoDigikam() {
        val out = Xmp.withRating(digikam, 3)
        assertEquals(XmpRead.Ok(3), Xmp.readRating(out))
        digikam.lines().filterNot { it.contains("<rdf:Description rdf:about=\"\"") }
            .forEach { assertTrue("Zeile fehlt: $it", out.contains(it)) }
    }

    @Test fun insertDeclaresNamespace() {
        val noNs = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about=""/></rdf:RDF></x:xmpmeta>"""
        assertEquals(XmpRead.Ok(4), Xmp.readRating(Xmp.withRating(noNs, 4)))
    }

    @Test fun otherPrefixIsRespected() {
        val other = fstop.replace("xmlns:xmp=", "xmlns:xap=").replace("xmp:Rating=", "xap:Rating=")
        assertEquals(XmpRead.Ok(4), Xmp.readRating(other))
        assertEquals(XmpRead.Ok(2), Xmp.readRating(Xmp.withRating(other, 2)))
    }

    @Test fun removeRating() = assertEquals(XmpRead.Ok(null), Xmp.readRating(Xmp.withRating(fstop, null)))

    @Test fun newSidecarRoundTrip() {
        val s = Xmp.newSidecar(5)
        assertEquals(XmpRead.Ok(5), Xmp.readRating(s))
        assertTrue(s.startsWith("<?xpacket begin="))
    }

    @Test(expected = IllegalArgumentException::class)
    fun brokenThrows() { Xmp.withRating(broken, 3) }
}
