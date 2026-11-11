package io.github.archonw.starswipe.ui.storages

import io.github.archonw.starswipe.db.StorageType
import io.github.archonw.starswipe.db.testDb
import io.github.archonw.starswipe.settings.MapSecrets
import io.github.archonw.starswipe.settings.StorageFactory
import io.github.archonw.starswipe.storage.InMemoryStorage
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.storage.dav.LoginStart
import io.github.archonw.starswipe.storage.dav.NcCredentials
import io.github.archonw.starswipe.storage.dav.LoginFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddStorageViewModelTest {
    private val db = testDb()
    private val secrets = MapSecrets()
    private var connectError: StorageException? = null
    private var polls = 0
    private var pollErrorsAt = emptySet<Int>()
    private val flow = object : LoginFlow {
        override suspend fun start(server: String) = LoginStart("$server/login", "tok", "$server/poll")
        override suspend fun pollOnce(start: LoginStart): NcCredentials? =
            if (++polls in pollErrorsAt) throw io.github.archonw.starswipe.storage.StorageException.Unreachable(java.io.IOException("Socket geschlossen"))
            else if (polls < 3) null else NcCredentials("https://cloud.de", "jan", "app-pw", "Jan1")
    }
    private val vm = AddStorageCore(db.locations(), secrets, flow, pollIntervalMs = 1) { _, _ ->
        InMemoryStorage(mapOf("a/b.jpg" to byteArrayOf(1))).apply { failWith = connectError }
    }
    @After fun close() = db.close()

    private fun all() = runBlocking { db.locations().all() }

    @Test fun oneStarDefaultPerType() {
        assertFalse(AddStorageCore.oneStarDefault(StorageType.NEXTCLOUD))
        listOf(StorageType.WEBDAV, StorageType.SMB, StorageType.LOCAL).forEach { assertTrue(AddStorageCore.oneStarDefault(it)) }
    }

    @Test fun connectFailureShowsMessageAndDoesNotSave() = runTest {
        connectError = StorageException.Unauthorized()
        val r = vm.connect(StorageForm(type = StorageType.SMB, name = "NAS", host = "192.168.178.20", share = "Bilder", user = "jan", password = "x"))
        assertEquals(AddStorageCore.MSG_UNAUTHORIZED, (r as ConnectResult.Error).message)
        assertTrue(all().isEmpty())
    }

    @Test fun unreachableMessageMentionsVpn() = runTest {
        connectError = StorageException.Unreachable(java.io.IOException())
        val r = vm.connect(StorageForm(type = StorageType.SMB, name = "NAS", host = "nas", share = "B", user = "j", password = "x"))
        assertEquals(AddStorageCore.MSG_UNREACHABLE, (r as ConnectResult.Error).message)
    }

    @Test fun connectSuccessSavesWithSecret() = runTest {
        val r = vm.connect(StorageForm(type = StorageType.SMB, name = "NAS", host = "192.168.178.20", share = "Bilder",
            basePath = "Test/Foto Upload", user = "jan", password = "geheim"))
        val id = (r as ConnectResult.Saved).id
        val loc = all().single()
        assertEquals(id, loc.id)
        assertEquals(StorageType.SMB, loc.type)
        assertTrue(loc.oneStarIsUnrated)
        assertTrue(loc.config.contains("\"basePath\":\"Test/Foto Upload\""))
        assertFalse(loc.config.contains("geheim"))
        assertEquals("geheim", secrets.get(StorageFactory.secretKey(id)))
    }

    @Test fun cleartextOutsideHomeRejected() = runTest {
        val r = vm.connect(StorageForm(type = StorageType.WEBDAV, name = "D", url = "http://cloud.example.de/dav", user = "j", password = "x"))
        assertEquals(AddStorageCore.MSG_HTTPS, (r as ConnectResult.Error).message)
        assertTrue(all().isEmpty())
    }

    @Test fun missingFieldsRejected() = runTest {
        val r = vm.connect(StorageForm(type = StorageType.SMB, name = "NAS", host = "", share = "B", user = "j", password = "x"))
        assertTrue(r is ConnectResult.Error)
    }

    @Test fun nextcloudPollStopsAfterSuccess() = runTest {
        var opened: String? = null
        val r = vm.loginNextcloud("cloud.de", name = "Cloud") { opened = it }
        assertEquals("https://cloud.de/login", opened)
        assertEquals(3, polls)
        val loc = all().single()
        assertEquals((r as ConnectResult.Saved).id, loc.id)
        assertEquals(StorageType.NEXTCLOUD, loc.type)
        assertFalse(loc.oneStarIsUnrated)
        assertTrue(loc.config.contains("\"userId\":\"Jan1\""))
        assertEquals("app-pw", secrets.get(StorageFactory.secretKey(loc.id)))
    }

    @Test fun localSavesTreeUri() = runTest {
        val id = vm.saveLocal("content://tree/primary%3ADCIM", "Handy DCIM")
        val loc = all().single()
        assertEquals(id, loc.id)
        assertEquals(listOf(""), loc.folders)
        assertNull(secrets.get(StorageFactory.secretKey(id)))
    }

    /** App im Hintergrund (Browser offen): ein Poll scheitert am Netz – weiter warten statt abbrechen. */
    @Test fun nextcloudPollSurvivesNetworkHiccup() = runTest {
        pollErrorsAt = setOf(2)
        val r = vm.loginNextcloud("cloud.de", name = "Cloud") { }
        assertTrue(r is ConnectResult.Saved)
        assertEquals(3, polls)
    }
}
