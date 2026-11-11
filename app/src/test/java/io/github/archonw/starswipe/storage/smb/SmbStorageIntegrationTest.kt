package io.github.archonw.starswipe.storage.smb

import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.PhotoStorageContract
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * Läuft nur mit SMB_TEST_HOST, SMB_TEST_SHARE, SMB_TEST_PATH, SMB_TEST_USER, SMB_TEST_PASS.
 * Legt einen frischen Unterordner _vertragstest_<zeit> an und räumt ihn wieder ab.
 */
class SmbStorageIntegrationTest : PhotoStorageContract() {
    private val env = System.getenv()
    private var storage: SmbStorage? = null

    @Before fun requireEnv() = assumeTrue("SMB_TEST_* nicht gesetzt", env["SMB_TEST_HOST"] != null)

    override fun create(files: Map<String, ByteArray>): PhotoStorage = runBlocking {
        val base = env["SMB_TEST_PATH"]!!.trim('/') + "/_vertragstest_${System.currentTimeMillis()}"
        val s = SmbStorage(SmbConfig(env["SMB_TEST_HOST"]!!, env["SMB_TEST_SHARE"]!!, base,
            env["SMB_TEST_USER"]!!, env["SMB_TEST_PASS"]!!, env["SMB_TEST_DOMAIN"]))
        s.mkdirs("")
        files.forEach { (p, d) -> s.write(p, d, null) }
        storage = s
        s
    }

    @After fun cleanup() = runBlocking { storage?.removeBaseRecursively(); Unit }
}
