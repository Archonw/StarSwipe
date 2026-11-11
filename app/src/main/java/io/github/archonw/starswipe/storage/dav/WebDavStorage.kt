package io.github.archonw.starswipe.storage.dav

import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.PreviewSource
import io.github.archonw.starswipe.storage.Signature
import io.github.archonw.starswipe.storage.StorageException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

open class WebDavStorage(
    baseUrl: String, user: String, password: String, http: OkHttpClient,
) : PhotoStorage {
    protected val dav = DavClient(baseUrl, user, password, http)
    /** Zeigt die Adresse auf eine Nextcloud (…/remote.php/dav/files/<user>/ oder …/remote.php/webdav/), deren Basis. */
    val nextcloudBase: String? = NEXTCLOUD_DAV.find(dav.root.toString())?.groupValues?.get(1)
    /** Auf einer Nextcloud zusätzlich Datei-ID und Aufnahmedatum abfragen (für Server-Vorschau und Sortierung). */
    protected open val extraProps: String = if (nextcloudBase != null) NEXTCLOUD_PROPS else ""

    override suspend fun list(dir: String): List<Entry> = dav.propfind(dir, dir = true, extraProps)

    override suspend fun read(path: String, maxBytes: Long?): ByteArray {
        val b = Request.Builder().url(dav.url(path)).get()
        if (maxBytes != null) b.header("Range", "bytes=0-${maxBytes - 1}")
        return dav.call(b.build(), path) { r ->
            val src = r.body!!.source()
            if (maxBytes == null) src.readByteArray()
            else { src.request(maxBytes); val buf = okio.Buffer(); src.read(buf, maxBytes); buf.readByteArray() }
        }
    }

    override suspend fun stat(path: String): Entry? = try {
        dav.propfind(path, dir = false, extraProps).firstOrNull()
    } catch (_: StorageException.NotFound) { null }

    override suspend fun write(path: String, data: ByteArray, expected: Signature?): Signature {
        val b = Request.Builder().url(dav.url(path)).put(data.toRequestBody(OCTET))
        when {
            expected == null -> b.header("If-None-Match", "*")
            expected.etag != null -> b.header("If-Match", "\"${expected.etag}\"")
            else -> { val cur = stat(path); if (cur == null || !expected.matches(cur)) throw StorageException.Conflict(path) }
        }
        dav.call(b.build(), path) { }
        return stat(path)?.signature ?: throw StorageException.Other("$path not found after writing")
    }

    override suspend fun delete(path: String, expected: Signature?) {
        val b = Request.Builder().url(dav.url(path)).delete()
        when {
            expected == null -> {}
            expected.etag != null -> b.header("If-Match", "\"${expected.etag}\"")
            else -> { val cur = stat(path) ?: throw StorageException.NotFound(path); if (!expected.matches(cur)) throw StorageException.Conflict(path) }
        }
        dav.call(b.build(), path) { }
    }

    override fun previewRequest(entry: Entry): PreviewSource {
        val base = nextcloudBase
        val id = entry.fileId
        if (base != null && id != null) return PreviewSource.Http(
            "$base/index.php/core/preview?fileId=$id&x=2048&y=2048&a=1", mapOf("Authorization" to dav.auth))
        return PreviewSource.Bytes("dav:${dav.root}${entry.path}:${entry.etag}") { read(entry.path) }
    }

    companion object {
        val OCTET = "application/octet-stream".toMediaType()
        const val NEXTCLOUD_PROPS = "<oc:fileid/><nc:metadata-photos-original_date_time/>"
        private val NEXTCLOUD_DAV = Regex("""^(https?://.+?)/remote\.php/(?:dav/files/[^/]+|webdav)/""")
    }
}
