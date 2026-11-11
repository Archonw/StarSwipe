package io.github.archonw.starswipe.xmp

import io.github.archonw.starswipe.xml.SafeXml
import org.w3c.dom.Element

sealed interface XmpRead {
    data class Ok(val rating: Int?) : XmpRead
    data object Broken : XmpRead
}

/**
 * Liest und ändert `xmp:Rating` in XMP-Sidecars.
 *
 * Geändert wird rein textuell an genau einer Stelle, damit alle übrigen Inhalte
 * (darktable-Verlauf, digiKam-Gesichter …) byte-genau erhalten bleiben.
 */
object Xmp {
    const val NS = "http://ns.adobe.com/xap/1.0/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    /** UTF-8 dekodieren, aber nur, wenn das verlustfrei zurück in dieselben Bytes geht. */
    fun decodeStrict(bytes: ByteArray): String? {
        val s = String(bytes, Charsets.UTF_8)
        return if (s.toByteArray(Charsets.UTF_8).contentEquals(bytes)) s else null
    }

    fun readRating(xml: String): XmpRead {
        val doc = parse(xml) ?: return XmpRead.Broken
        val raw = findRating(doc.documentElement) ?: return XmpRead.Ok(null)
        val value = raw.trim().toDoubleOrNull()?.toInt()
        return XmpRead.Ok(value?.takeIf { it in 1..5 })
    }

    fun withRating(xml: String, rating: Int?): String {
        require(rating == null || rating in 1..5) { "Rating $rating außerhalb 1..5" }
        require(parse(xml) != null) { "XMP ist kein gültiges XML" }

        val prefixes = declaredPrefixes(xml)
        for (p in prefixes) {
            val attr = Regex("""(\s+)${Regex.escape(p)}:Rating\s*=\s*(["'])[^"']*\2""")
            attr.find(xml)?.let { m ->
                val replacement = if (rating == null) "" else "${m.groupValues[1]}$p:Rating=\"$rating\""
                return verified(xml.replaceRange(m.range, replacement), rating)
            }
            val elem = Regex("""(\s*)<${Regex.escape(p)}:Rating\s*>[^<]*</${Regex.escape(p)}:Rating\s*>""")
            elem.find(xml)?.let { m ->
                val replacement = if (rating == null) "" else "${m.groupValues[1]}<$p:Rating>$rating</$p:Rating>"
                return verified(xml.replaceRange(m.range, replacement), rating)
            }
        }
        if (rating == null) return xml

        val desc = Regex("""<rdf:Description\b""").find(xml)
            ?: throw IllegalArgumentException("Kein rdf:Description gefunden")
        val tagEnd = startTagEnd(xml, desc.range.first)
        val inScope = prefixes.firstOrNull { p ->
            val decl = Regex("""xmlns:${Regex.escape(p)}\s*=""").find(xml)
            decl != null && decl.range.first < tagEnd
        }
        val insertion = if (inScope != null) {
            " $inScope:Rating=\"$rating\""
        } else {
            val p = if (Regex("""xmlns:xmp\s*=""").containsMatchIn(xml)) "xapR" else "xmp"
            " xmlns:$p=\"$NS\" $p:Rating=\"$rating\""
        }
        val at = desc.range.last + 1
        return verified(xml.substring(0, at) + insertion + xml.substring(at), rating)
    }

    fun newSidecar(rating: Int): String {
        require(rating in 1..5)
        return """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="StarSwipe">
 <rdf:RDF xmlns:rdf="$RDF_NS">
  <rdf:Description rdf:about=""
    xmlns:xmp="$NS"
   xmp:Rating="$rating"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
"""
    }

    private fun verified(out: String, rating: Int?): String {
        check(readRating(out) == XmpRead.Ok(rating)) { "Rating konnte nicht sicher gesetzt werden" }
        return out
    }

    private fun declaredPrefixes(xml: String): List<String> =
        Regex("""xmlns:([A-Za-z_][\w.-]*)\s*=\s*["']${Regex.escape(NS)}["']""")
            .findAll(xml).map { it.groupValues[1] }.distinct().toList()

    /** Index des schließenden '>' des Start-Tags ab [from], Anführungszeichen werden beachtet. */
    private fun startTagEnd(xml: String, from: Int): Int {
        var quote: Char? = null
        for (i in from until xml.length) {
            val c = xml[i]
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return i
        }
        throw IllegalArgumentException("Start-Tag nicht geschlossen")
    }

    private fun findRating(e: Element): String? {
        if (e.namespaceURI == RDF_NS && e.localName == "Description") {
            e.getAttributeNodeNS(NS, "Rating")?.let { return it.value }
        }
        if (e.namespaceURI == NS && e.localName == "Rating") return e.textContent
        val children = e.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c is Element) findRating(c)?.let { return it }
        }
        return null
    }

    private fun parse(xml: String) = try {
        SafeXml.parse(xml)
    } catch (_: Exception) {
        null
    }
}
