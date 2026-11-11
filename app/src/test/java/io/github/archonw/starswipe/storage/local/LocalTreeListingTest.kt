package io.github.archonw.starswipe.storage.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Ein Ordner mit vielen Fotos muss mit einer einzigen Abfrage gelistet werden (nicht 5 Abfragen je Datei). */
@RunWith(RobolectricTestRunner::class)
class LocalTreeListingTest {
    class FakeDocs : ContentProvider() {
        companion object {
            var queries = 0
            /** docId → (Name, MIME, Größe, Änderungszeit, Eltern-docId) */
            val docs = linkedMapOf<String, List<Any?>>()
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            queries++
            val cols = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
            val c = MatrixCursor(cols)
            val segs = uri.pathSegments
            val rows = if (segs.last() == "children") docs.filter { it.value[4] == segs[segs.size - 2] }
                       else docs.filterKeys { it == segs.last() }
            for ((id, v) in rows) c.addRow(cols.map { col -> when (col) {
                Document.COLUMN_DOCUMENT_ID -> id; Document.COLUMN_DISPLAY_NAME -> v[0]; Document.COLUMN_MIME_TYPE -> v[1]
                Document.COLUMN_SIZE -> v[2]; Document.COLUMN_LAST_MODIFIED -> v[3]; else -> null } })
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private val tree = DocumentsContract.buildTreeDocumentUri("test.docs", "root")
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setup() {
        Robolectric.setupContentProvider(FakeDocs::class.java, "test.docs")
        FakeDocs.queries = 0
        FakeDocs.docs.clear()
        FakeDocs.docs["root"] = listOf("DCIM", Document.MIME_TYPE_DIR, 0L, 0L, null)
        FakeDocs.docs["cam"] = listOf("Camera", Document.MIME_TYPE_DIR, 0L, 0L, "root")
        for (i in 1..500) FakeDocs.docs["f$i"] = listOf("IMG_$i.jpg", "image/jpeg", 1000L + i, 1_700_000_000_000L + i, "cam")
    }

    @Test fun listsBigFolderWithFewQueries() = runBlocking {
        val s = LocalStorage(ctx, tree)
        FakeDocs.queries = 0
        val entries = s.list("Camera")
        assertEquals(500, entries.size)
        val e = entries.first { it.name == "IMG_7.jpg" }
        assertEquals("Camera/IMG_7.jpg", e.path)
        assertEquals(1007L, e.size)
        assertEquals(1_700_000_000_007L, e.modified.toEpochMilli())
        assertEquals(false, e.isDir)
        assertEquals("image/jpeg", e.contentType)
        assertTrue("Abfragen: ${FakeDocs.queries}", FakeDocs.queries <= 3)
    }

    @Test fun rootListingMarksFolders() = runBlocking {
        val s = LocalStorage(ctx, tree)
        val root = s.list("")
        assertEquals(listOf("Camera"), root.map { it.path })
        assertEquals(true, root.single().isDir)
    }

    private fun assertTrue(msg: String, b: Boolean) = org.junit.Assert.assertTrue(msg, b)
}
