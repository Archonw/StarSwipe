package io.github.archonw.starswipe.ui.rate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.archonw.starswipe.storage.PreviewSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException

/** Vorschau kam nicht rechtzeitig; die Oberfläche zeigt dazu einen übersetzten Text. */
class PreviewTimeoutException(val seconds: Long) : IOException("preview timeout after $seconds s")

/** Lädt Vorschaubilder für alle Speichertypen; Coil skaliert beim Dekodieren auf die Zielgröße. */
class PreviewFetcher(
    private val src: PreviewSource,
    private val options: Options,
    private val context: Context,
    private val http: OkHttpClient,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val cache: PreviewCache? = null,
) : Fetcher {
    companion object {
        const val TIMEOUT_MS = 45_000L
        const val MAX_EDGE = 2048
        private val LOADERS = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Verkleinert ein volles Foto auf höchstens [MAX_EDGE] px (Drehung laut EXIF wird eingerechnet), als JPEG.
         * Kleine oder nicht dekodierbare Bilder (z. B. RAW) bleiben unverändert.
         */
        fun shrink(bytes: ByteArray): ByteArray {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth; val h = bounds.outHeight
            if (w <= 0 || h <= 0 || maxOf(w, h) <= MAX_EDGE) return bytes
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= MAX_EDGE) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return bytes
            val f = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            var bmp = if (f < 1f) Bitmap.createScaledBitmap(decoded, Math.round(decoded.width * f), Math.round(decoded.height * f), true) else decoded
            val rotation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
            if (rotation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }
    }

    private fun cacheKey(): String? = when (src) {
        is PreviewSource.Http -> src.url
        is PreviewSource.Bytes -> src.key
        is PreviewSource.ContentUri -> null // liegt ohnehin auf dem Handy
    }

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val key = cacheKey()
        if (key != null) cache?.get(key)?.let { f ->
            return@withContext SourceFetchResult(ImageSource(f.toOkioPath(), options.fileSystem), null, DataSource.DISK)
        }
        val bytes = loadWithTimeout()
        // Volle Originale (SMB/WebDAV) verkleinert ablegen; Server-Vorschauen sind schon klein.
        val stored = if (key != null && cache != null) (if (src is PreviewSource.Bytes) shrink(bytes) else bytes).also { cache.put(key, it) } else bytes
        SourceFetchResult(ImageSource(Buffer().write(stored), options.fileSystem), null, DataSource.NETWORK)
    }

    private suspend fun loadWithTimeout(): ByteArray {
        // Eigener Scope: ein blockierender Lesezugriff (SMB) hält das Warten nicht fest; er läuft im Hintergrund aus.
        val job = LOADERS.async { load() }
        return try { withTimeout(timeoutMs) { job.await() } } catch (e: TimeoutCancellationException) {
            job.cancel()
            throw PreviewTimeoutException(timeoutMs / 1000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            job.cancel(); throw e // Foto weggewischt
        }
    }

    private suspend fun load(): ByteArray = withContext(Dispatchers.IO) {
        when (src) {
            is PreviewSource.Http -> {
                val req = Request.Builder().url(src.url).apply { src.headers.forEach { (k, v) -> header(k, v) } }.build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("preview HTTP ${r.code}")
                    r.body!!.bytes()
                }
            }
            is PreviewSource.Bytes -> src.load()
            is PreviewSource.ContentUri -> context.contentResolver.openInputStream(Uri.parse(src.uri))?.use { it.readBytes() }
                ?: throw IOException("file not readable")
        }
    }

    class Factory(private val context: Context, private val http: OkHttpClient, private val cache: PreviewCache?) : Fetcher.Factory<PreviewSource> {
        override fun create(data: PreviewSource, options: Options, imageLoader: ImageLoader): Fetcher =
            PreviewFetcher(data, options, context, http, cache = cache)
    }

    object Key : Keyer<PreviewSource> {
        override fun key(data: PreviewSource, options: Options): String = when (data) {
            is PreviewSource.Http -> data.url
            is PreviewSource.Bytes -> data.key
            is PreviewSource.ContentUri -> data.uri
        }
    }
}

fun previewImageLoader(context: Context, http: OkHttpClient): ImageLoader =
    ImageLoader.Builder(context).components {
        add(PreviewFetcher.Factory(context.applicationContext, http,
            PreviewCache(java.io.File(context.cacheDir, "vorschau"), maxBytes = 500L * 1024 * 1024)))
        add(PreviewFetcher.Key)
    }.build()
