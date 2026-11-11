package io.github.archonw.starswipe.storage.local

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.PhotoStorageContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class LocalStorageTest : PhotoStorageContract() {
    private lateinit var dir: File

    override fun create(files: Map<String, ByteArray>): PhotoStorage {
        dir = Files.createTempDirectory("lokal").toFile()
        files.forEach { (p, d) -> File(dir, p).apply { parentFile!!.mkdirs(); writeBytes(d) } }
        return LocalStorage(ApplicationProvider.getApplicationContext(), DocumentFile.fromFile(dir))
    }

    @Test fun createdSidecarKeepsExactName() = runBlocking {
        val s = create(TREE)
        s.write("Album/a.jpg.xmp", "<x/>".toByteArray(), null)
        assertEquals(setOf("a.jpg", "a.jpg.xmp", "Unter"), File(dir, "Album").list()!!.toSet())
        assertTrue(File(dir, "Album/a.jpg.xmp").readText() == "<x/>")
    }
}
