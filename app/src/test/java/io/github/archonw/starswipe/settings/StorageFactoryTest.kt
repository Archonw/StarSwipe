package io.github.archonw.starswipe.settings

import androidx.test.core.app.ApplicationProvider
import io.github.archonw.starswipe.db.StorageLocationEntity
import io.github.archonw.starswipe.db.StorageType
import io.github.archonw.starswipe.storage.dav.NextcloudStorage
import io.github.archonw.starswipe.storage.dav.WebDavStorage
import io.github.archonw.starswipe.storage.local.LocalStorage
import io.github.archonw.starswipe.storage.smb.SmbStorage
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class MapSecrets : Secrets {
    val m = mutableMapOf<String, String>()
    override fun put(key: String, value: String) { m[key] = value }
    override fun get(key: String) = m[key]
    override fun remove(key: String) { m.remove(key) }
}

@RunWith(RobolectricTestRunner::class)
class StorageFactoryTest {
    private val secrets = MapSecrets()
    private val f = StorageFactory(ApplicationProvider.getApplicationContext(), secrets, OkHttpClient())
    private fun loc(id: Long, t: StorageType, cfg: String) = StorageLocationEntity(id, t, "x", cfg, emptyList(), true, false)

    @Test fun buildsEachType() {
        (1L..3L).forEach { secrets.put(StorageFactory.secretKey(it), "pw") }
        assertTrue(f.create(loc(1, StorageType.NEXTCLOUD, """{"server":"https://c.de","loginName":"jan","userId":"jan"}""")) is NextcloudStorage)
        val dav = f.create(loc(2, StorageType.WEBDAV, """{"url":"https://c.de/dav/","user":"jan"}"""))
        assertTrue(dav is WebDavStorage && dav !is NextcloudStorage)
        assertTrue(f.create(loc(3, StorageType.SMB, """{"host":"nas","share":"Bilder","basePath":"","user":"jan"}""")) is SmbStorage)
        assertTrue(f.create(loc(4, StorageType.LOCAL, """{"treeUri":"file:///tmp"}""")) is LocalStorage)
    }

    @Test(expected = IllegalStateException::class)
    fun missingSecretThrows() { f.create(loc(9, StorageType.WEBDAV, """{"url":"https://c.de/dav/","user":"jan"}""")) }
}
