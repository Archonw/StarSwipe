package io.github.archonw.starswipe.storage.dav

import io.github.archonw.starswipe.storage.Entry
import okhttp3.HttpUrl
import org.w3c.dom.Element
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import io.github.archonw.starswipe.xml.SafeXml

/** Parser für WebDAV-207-Antworten (PROPFIND). */
object Multistatus {
    private const val DAV = "DAV:"
    private const val OC = "http://owncloud.org/ns"
    private const val NC = "http://nextcloud.org/ns"

    /** Einträge relativ zu [root]; der angefragte Ordner [requested] selbst wird ausgelassen. */
    fun parse(body: String, root: HttpUrl, requested: String): List<Entry> {
        val doc = SafeXml.parse(body)
        val rootSegs = root.pathSegments.filter { it.isNotEmpty() }
        val out = mutableListOf<Entry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.child(DAV, "href")?.textContent?.trim() ?: continue
            val segs = (root.resolve(href) ?: continue).pathSegments.filter { it.isNotEmpty() }
            if (segs.size < rootSegs.size || segs.subList(0, rootSegs.size) != rootSegs) continue
            val path = segs.drop(rootSegs.size).joinToString("/")
            if (path == requested.trim('/')) continue
            val props = r.okProps()
            fun p(ns: String, name: String) = props.firstNotNullOfOrNull { it.child(ns, name) }
            val isDir = p(DAV, "resourcetype")?.child(DAV, "collection") != null
            out += Entry(
                path = path,
                name = path.substringAfterLast('/'),
                isDir = isDir,
                size = p(DAV, "getcontentlength")?.textContent?.trim()?.toLongOrNull() ?: 0,
                modified = p(DAV, "getlastmodified")?.textContent?.trim()?.let(::parseDate) ?: Instant.EPOCH,
                etag = p(DAV, "getetag")?.textContent?.trim()?.trim('"')?.removePrefix("W/")?.trim('"')?.ifEmpty { null },
                contentType = p(DAV, "getcontenttype")?.textContent?.trim()?.ifEmpty { null },
                fileId = p(OC, "fileid")?.textContent?.trim()?.toLongOrNull(),
                takenAt = p(NC, "metadata-photos-original_date_time")?.textContent?.trim()?.toLongOrNull()
                    ?.let { LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault()) },
            )
        }
        return out
    }

    private fun parseDate(s: String): Instant? =
        try { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() } catch (_: Exception) { null }

    private fun Element.okProps(): List<Element> {
        val res = mutableListOf<Element>()
        val ps = getElementsByTagNameNS(DAV, "propstat")
        for (i in 0 until ps.length) {
            val st = ps.item(i) as Element
            if (st.child(DAV, "status")?.textContent?.contains(" 200 ") == true) st.child(DAV, "prop")?.let { res += it }
        }
        return res
    }

    private fun Element.child(ns: String, name: String): Element? {
        val c = childNodes
        for (i in 0 until c.length) {
            val n = c.item(i)
            if (n is Element && n.namespaceURI == ns && n.localName == name) return n
        }
        return null
    }
}
