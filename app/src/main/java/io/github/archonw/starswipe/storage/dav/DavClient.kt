package io.github.archonw.starswipe.storage.dav

import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** Dünne WebDAV-Schicht: Requests bauen, Fehler auf [StorageException] abbilden. */
class DavClient(rootUrl: String, user: String, password: String, private val http: OkHttpClient) {
    val root: HttpUrl = rootUrl.let { if (it.endsWith("/")) it else "$it/" }.toHttpUrl()
    val auth: String = Credentials.basic(user, password, Charsets.UTF_8)

    fun url(path: String, dir: Boolean = false): HttpUrl {
        val b = root.newBuilder()
        // root endet mit '/', dadurch ist das letzte Segment leer; ersetzen statt anhängen
        if (b.build().pathSegments.lastOrNull() == "") b.removePathSegment(b.build().pathSegments.size - 1)
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (dir) b.addPathSegment("")
        return b.build()
    }

    fun propfindBody(extraProps: String): String =
        """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop>""" +
            "<d:getlastmodified/><d:getcontentlength/><d:resourcetype/><d:getetag/><d:getcontenttype/>$extraProps" +
            "</d:prop></d:propfind>"

    suspend fun propfind(path: String, dir: Boolean, extraProps: String = ""): List<Entry> {
        val req = Request.Builder().url(url(path, dir)).header("Depth", if (dir) "1" else "0")
            .method("PROPFIND", propfindBody(extraProps).toRequestBody(XML))
            .build()
        return call(req, path) { r -> Multistatus.parse(r.body!!.string(), root, if (dir) path else "\u0000") }
    }

    suspend fun <T> call(req: Request, path: String, ok: (Response) -> T): T = withContext(Dispatchers.IO) {
        val withAuth = req.newBuilder().header("Authorization", auth).build()
        val resp = try { http.newCall(withAuth).execute() } catch (e: IOException) { throw StorageException.Unreachable(e) }
        resp.use { r ->
            when (r.code) {
                in 200..299 -> ok(r)
                401, 403 -> throw StorageException.Unauthorized()
                404 -> throw StorageException.NotFound(path)
                409, 412 -> throw StorageException.Conflict(path)
                else -> throw StorageException.Other("HTTP ${r.code} for $path")
            }
        }
    }

    companion object { val XML = "application/xml; charset=utf-8".toMediaType() }
}
