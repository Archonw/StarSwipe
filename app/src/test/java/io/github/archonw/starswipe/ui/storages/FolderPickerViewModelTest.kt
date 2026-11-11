package io.github.archonw.starswipe.ui.storages

import io.github.archonw.starswipe.db.location
import io.github.archonw.starswipe.db.testDb
import io.github.archonw.starswipe.storage.InMemoryStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderPickerViewModelTest {
    private val db = testDb()
    private val storage = InMemoryStorage(mapOf("Fotos/2024/a.jpg" to byteArrayOf(1), "Fotos/2025/b.jpg" to byteArrayOf(1), "Musik/x.mp3" to byteArrayOf(1)))
    @After fun close() = db.close()

    private fun core(): Pair<Long, FolderPickerCore> = runBlocking {
        val id = db.locations().insert(location(folders = emptyList()))
        id to FolderPickerCore(id, db.locations(), storage)
    }

    @Test fun navigatesIntoSubfolder() = runTest {
        val (_, c) = core()
        assertEquals(listOf("Fotos", "Musik"), c.open("").map { it.name })
        assertEquals(listOf("Fotos/2024", "Fotos/2025"), c.open("Fotos").map { it.path })
    }

    @Test fun togglesAndSavesFolders() = runTest {
        val (id, c) = core()
        c.load()
        c.toggle("Fotos/2024"); c.toggle("Musik"); c.toggle("Musik")
        assertEquals(setOf("Fotos/2024"), c.selected)
        c.save()
        assertEquals(listOf("Fotos/2024"), db.locations().get(id)!!.folders)
    }

    @Test fun loadsExistingSelection() = runTest {
        val id = db.locations().insert(location(folders = listOf("Musik")))
        val c = FolderPickerCore(id, db.locations(), storage)
        c.load()
        assertTrue("Musik" in c.selected)
    }

    /** Ordner ohne Unterordner: nichts anzuhaken – „Fertig“ nimmt dann den gerade offenen Ordner. */
    @Test fun saveWithoutSelectionUsesCurrentFolder() = runTest {
        val (id, c) = core()
        c.load()
        c.save(current = "Fotos/2024")
        assertEquals(listOf("Fotos/2024"), db.locations().get(id)!!.folders)
    }

    @Test fun saveWithoutSelectionAtRootUsesRoot() = runTest {
        val (id, c) = core()
        c.load()
        c.save(current = "")
        assertEquals(listOf(""), db.locations().get(id)!!.folders)
    }
}
