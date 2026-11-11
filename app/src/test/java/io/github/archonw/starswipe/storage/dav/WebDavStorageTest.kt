package io.github.archonw.starswipe.storage.dav

import io.github.archonw.starswipe.storage.InMemoryStorage
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.PhotoStorageContract
import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WebDavStorageTest : PhotoStorageContract() {
    private val server = MockWebServer()
    private lateinit var fake: FakeDavServer

    override fun create(files: Map<String, ByteArray>): PhotoStorage {
        fake = FakeDavServer(InMemoryStorage(files))
        server.dispatcher = fake
        server.start()
        return WebDavStorage(server.url("/dav/").toString(), "jan", "geheim", OkHttpClient())
    }

    @After fun stop() = server.shutdown()

    @Test fun sendsRangeHeaderForMaxBytes() = runBlocking {
        val s = create(TREE)
        s.read("Album/a.jpg", 4)
        assertEquals("bytes=0-3", fake.requests.last().getHeader("Range"))
    }

    @Test fun encodesPathSegments() = runBlocking {
        val s = create(TREE)
        s.read("Foto Upload/Bäume #1.jpg")
        assertEquals("/dav/Foto%20Upload/B%C3%A4ume%20%231.jpg", fake.requests.last().requestUrl!!.encodedPath)
    }

    @Test fun wrongPasswordIsUnauthorized() = runBlocking {
        create(TREE)
        val s = WebDavStorage(server.url("/dav/").toString(), "jan", "falsch", OkHttpClient())
        try { s.list(""); fail() } catch (_: StorageException.Unauthorized) {}
    }

    @Test fun unreachableServer() = runBlocking {
        val s = WebDavStorage("http://127.0.0.1:1/dav/", "jan", "geheim", OkHttpClient())
        try { s.list(""); fail() } catch (_: StorageException.Unreachable) {}
    }

    /** Generisches WebDAV, das auf eine Nextcloud zeigt: Server-Vorschau statt ganzer Datei. */
    @Test fun nextcloudUrlUsesServerPreview() {
        val s = WebDavStorage("https://cloud.example.de/remote.php/dav/files/jan/", "jan", "pw", OkHttpClient())
        val e = io.github.archonw.starswipe.storage.Entry("Fotos/a.jpg", "a.jpg", false, 1, java.time.Instant.EPOCH, "e", null, 4711, null)
        val p = s.previewRequest(e) as io.github.archonw.starswipe.storage.PreviewSource.Http
        assertEquals("https://cloud.example.de/index.php/core/preview?fileId=4711&x=2048&y=2048&a=1", p.url)
        assertEquals(okhttp3.Credentials.basic("jan", "pw"), p.headers["Authorization"])
    }

    @Test fun nextcloudUrlAsksForFileId() {
        val s = WebDavStorage("https://cloud.example.de/nc/remote.php/webdav/", "jan", "pw", OkHttpClient())
        assertEquals("https://cloud.example.de/nc", s.nextcloudBase)
    }

    @Test fun otherServerHasNoNextcloudBase() {
        val s = WebDavStorage("https://nas.example.de/webdav/", "jan", "pw", OkHttpClient())
        assertEquals(null, s.nextcloudBase)
    }

    @Test fun listOnNextcloudRequestsFileId() = runBlocking {
        create(TREE)
        val s = WebDavStorage(server.url("/remote.php/dav/files/jan/").toString(), "jan", "geheim", OkHttpClient())
        runCatching { s.list("") }
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("oc:fileid"))
    }

    private fun assertTrue(msg: String, b: Boolean) = org.junit.Assert.assertTrue(msg, b)
}
