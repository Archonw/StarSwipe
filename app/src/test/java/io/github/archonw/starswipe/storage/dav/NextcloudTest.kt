package io.github.archonw.starswipe.storage.dav

import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.InMemoryStorage
import io.github.archonw.starswipe.storage.PreviewSource
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NextcloudTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val login = NextcloudLogin(OkHttpClient())

    @After fun stop() = server.shutdown()

    @Test fun startParsesLoginAndPoll() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"poll":{"token":"tok","endpoint":"$base/login/v2/poll"},"login":"$base/login/v2/flow/abc"}"""))
        val s = login.start(base)
        assertEquals(LoginStart("$base/login/v2/flow/abc", "tok", "$base/login/v2/poll"), s)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/index.php/login/v2", r.path)
    }

    @Test fun pollReturnsNullOn404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(login.pollOnce(LoginStart("x", "tok", "$base/login/v2/poll")))
        assertEquals("token=tok", server.takeRequest().body.readUtf8())
    }

    @Test fun pollReturnsCredentialsWithUserId() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"server":"$base","loginName":"jan@familie.de","appPassword":"app-pw"}"""))
        server.enqueue(MockResponse().setBody("""{"ocs":{"meta":{"status":"ok"},"data":{"id":"Jan1"}}}"""))
        val c = login.pollOnce(LoginStart("x", "tok", "$base/login/v2/poll"))
        assertEquals(NcCredentials(base, "jan@familie.de", "app-pw", "Jan1"), c)
        server.takeRequest()
        val who = server.takeRequest()
        assertEquals("/ocs/v2.php/cloud/user?format=json", who.path)
        assertEquals("true", who.getHeader("OCS-APIRequest"))
        assertEquals(Credentials.basic("jan@familie.de", "app-pw"), who.getHeader("Authorization"))
    }

    @Test fun revokeSendsOcsHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        login.revoke(NcCredentials(base, "jan", "app-pw", "jan"))
        val r = server.takeRequest()
        assertEquals("DELETE", r.method)
        assertEquals("/ocs/v2.php/core/apppassword", r.path)
        assertEquals("true", r.getHeader("OCS-APIRequest"))
    }

    @Test fun previewUsesFileId() {
        val s = NextcloudStorage(NcCredentials(base, "jan", "pw", "jan"), OkHttpClient())
        val e = Entry("A/a.jpg", "a.jpg", false, 1, Instant.EPOCH, "e", "image/jpeg", 4711, null)
        val p = s.previewRequest(e) as PreviewSource.Http
        assertEquals("$base/index.php/core/preview?fileId=4711&x=2048&y=2048&a=1", p.url)
        assertEquals(Credentials.basic("jan", "pw"), p.headers["Authorization"])
        assertTrue(s.previewRequest(e.copy(fileId = null)) is PreviewSource.Bytes)
    }

    @Test fun davBaseUsesUserId() = runBlocking {
        val fake = FakeDavServer(InMemoryStorage(mapOf("Photos/a.jpg" to byteArrayOf(1))),
            root = "/remote.php/dav/files/Jan1/", user = "jan@familie.de", password = "pw")
        server.dispatcher = fake
        val s = NextcloudStorage(NcCredentials(base, "jan@familie.de", "pw", "Jan1"), OkHttpClient())
        assertEquals(listOf("Photos/a.jpg"), s.list("Photos").map { it.path })
        assertTrue(fake.requests.last().body.readUtf8().contains("oc:fileid"))
    }
}
