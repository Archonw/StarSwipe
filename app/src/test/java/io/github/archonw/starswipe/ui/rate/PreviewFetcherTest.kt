package io.github.archonw.starswipe.ui.rate

import androidx.test.core.app.ApplicationProvider
import coil3.request.Options
import io.github.archonw.starswipe.storage.PreviewSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PreviewFetcherTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Ein hängender Speicher darf das Bild nicht ewig schwarz lassen: nach der Zeitgrenze kommt ein Fehler. */
    @Test fun hangingSourceTimesOutWithError() = runBlocking {
        val src = PreviewSource.Bytes("k") { awaitCancellation() }
        expectTimeout(src)
    }

    /** SMB-Lesen blockiert den Thread und reagiert nicht auf Abbruch – trotzdem muss der Fehler rechtzeitig kommen. */
    @Test fun blockingSourceTimesOutWithError() = runBlocking {
        val src = PreviewSource.Bytes("k") { Thread.sleep(5_000); ByteArray(0) }
        val t0 = System.currentTimeMillis()
        expectTimeout(src)
        assertTrue("dauerte ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
    }

    private suspend fun expectTimeout(src: PreviewSource) {
        try {
            PreviewFetcher(src, Options(ctx), ctx, OkHttpClient(), timeoutMs = 100).fetch()
            fail("kein Fehler")
        } catch (e: IOException) {
            assertTrue("$e", e is PreviewTimeoutException)
        }
    }

    /** Zweiter Abruf desselben Fotos kommt aus dem Speicher des Handys, ohne erneut zu laden. */
    @Test fun secondFetchComesFromDiskCache() = runBlocking {
        var loads = 0
        val img = jpeg(100, 80)
        val src = PreviewSource.Bytes("smb://x/a.jpg:1") { loads++; img }
        val cache = PreviewCache(java.nio.file.Files.createTempDirectory("c").toFile(), 1_000_000)
        PreviewFetcher(src, Options(ctx), ctx, OkHttpClient(), cache = cache).fetch()
        val r = PreviewFetcher(src, Options(ctx), ctx, OkHttpClient(), cache = cache).fetch() as coil3.fetch.SourceFetchResult
        assertEquals(1, loads)
        assertEquals(coil3.decode.DataSource.DISK, r.dataSource)
    }

    /** Volles Foto von SMB/WebDAV wird für den Speicher auf höchstens 2048 px verkleinert. */
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun bigOriginalIsShrunk() {
        val small = PreviewFetcher.shrink(jpeg(4000, 3000))
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(small, 0, small.size, o)
        assertEquals(2048, maxOf(o.outWidth, o.outHeight))
        assertEquals(1536, minOf(o.outWidth, o.outHeight))
    }

    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
}
