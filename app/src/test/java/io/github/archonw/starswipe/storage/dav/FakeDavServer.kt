package io.github.archonw.starswipe.storage.dav

import io.github.archonw.starswipe.storage.InMemoryStorage
import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Kleiner WebDAV-Server über einem [InMemoryStorage], wie Nextcloud ihn beantwortet. */
class FakeDavServer(
    val backing: InMemoryStorage,
    private val root: String = "/dav/",
    private val user: String = "jan",
    private val password: String = "geheim",
) : Dispatcher() {
    val requests = mutableListOf<RecordedRequest>()

    override fun dispatch(request: RecordedRequest): MockResponse = runBlocking {
        requests += request
        if (request.getHeader("Authorization") != Credentials.basic(user, password)) return@runBlocking MockResponse().setResponseCode(401)
        val url = request.requestUrl!!
        val rootSegs = root.trim('/').split('/').size
        val rel = url.pathSegments.drop(rootSegs).filter { it.isNotEmpty() }.joinToString("/")
        try {
            when (request.method) {
                "PROPFIND" -> propfind(url, rel)
                "GET" -> get(request, rel)
                "PUT" -> put(request, rel)
                "DELETE" -> delete(request, rel)
                else -> MockResponse().setResponseCode(405)
            }
        } catch (_: StorageException.NotFound) {
            MockResponse().setResponseCode(404)
        }
    }

    private fun href(rel: String, dir: Boolean): String {
        val b = HttpUrl.Builder().scheme("http").host("x").addPathSegments(root.trim('/'))
        rel.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        val p = b.build().encodedPath
        return if (dir) "$p/" else p
    }

    private suspend fun propfind(url: HttpUrl, rel: String): MockResponse {
        val sb = StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">")
        val file = backing.stat(rel)
        if (file != null) {
            sb.append(resp(rel, false, file.size, file.etag, file.modified))
        } else {
            val children = backing.list(rel) // wirft NotFound
            sb.append(resp(rel, true, 0, "d", java.time.Instant.EPOCH))
            children.forEach { sb.append(resp(it.path, it.isDir, it.size, it.etag, it.modified)) }
        }
        sb.append("</d:multistatus>")
        return MockResponse().setResponseCode(207).setBody(sb.toString())
    }

    private fun resp(rel: String, dir: Boolean, size: Long, etag: String?, mod: java.time.Instant): String {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(mod.atOffset(ZoneOffset.UTC))
        val type = if (dir) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/><d:getcontentlength>$size</d:getcontentlength><d:getcontenttype>image/jpeg</d:getcontenttype>"
        return "<d:response><d:href>${href(rel, dir)}</d:href><d:propstat><d:prop><d:getlastmodified>$date</d:getlastmodified>$type<d:getetag>\"${etag ?: ""}\"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }

    private suspend fun get(r: RecordedRequest, rel: String): MockResponse {
        val data = backing.read(rel)
        val range = r.getHeader("Range")
        if (range != null) {
            val end = range.removePrefix("bytes=0-").toInt()
            return MockResponse().setResponseCode(206).setBody(Buffer().write(data.copyOf(minOf(end + 1, data.size))))
        }
        return MockResponse().setBody(Buffer().write(data))
    }

    private suspend fun put(r: RecordedRequest, rel: String): MockResponse {
        val cur = backing.stat(rel)
        if (r.getHeader("If-None-Match") == "*" && cur != null) return MockResponse().setResponseCode(412)
        r.getHeader("If-Match")?.let { if (cur == null || "\"${cur.etag}\"" != it) return MockResponse().setResponseCode(412) }
        backing.put(rel, r.body.readByteArray())
        return MockResponse().setResponseCode(if (cur == null) 201 else 204).setHeader("ETag", "\"${backing.stat(rel)!!.etag}\"")
    }

    private suspend fun delete(r: RecordedRequest, rel: String): MockResponse {
        val cur = backing.stat(rel) ?: return MockResponse().setResponseCode(404)
        r.getHeader("If-Match")?.let { if ("\"${cur.etag}\"" != it) return MockResponse().setResponseCode(412) }
        backing.delete(rel, null)
        return MockResponse().setResponseCode(204)
    }
}
