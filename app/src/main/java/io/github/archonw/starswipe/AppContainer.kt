package io.github.archonw.starswipe

import android.content.Context
import androidx.room.Room
import io.github.archonw.starswipe.db.AppDatabase
import io.github.archonw.starswipe.db.StorageLocationEntity
import io.github.archonw.starswipe.db.StorageType
import io.github.archonw.starswipe.library.LibraryRepository
import io.github.archonw.starswipe.library.ScanService
import io.github.archonw.starswipe.rating.RatingService
import io.github.archonw.starswipe.rating.RatingWorker
import io.github.archonw.starswipe.settings.SecretStore
import io.github.archonw.starswipe.settings.Settings
import io.github.archonw.starswipe.settings.StorageFactory
import io.github.archonw.starswipe.storage.CleartextGuard
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.dav.NextcloudLogin
import io.github.archonw.starswipe.ui.rate.previewImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hält alle langlebigen Objekte der App (statt DI-Framework). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "starswipe.db").build()
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(CleartextGuard)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val secrets = SecretStore(app)
    val settings = Settings(app)
    val factory = StorageFactory(app, secrets, http)
    val nextcloudLogin = NextcloudLogin(http)
    val imageLoader by lazy { previewImageLoader(app, http) }

    private val cacheLock = Mutex()
    private val cache = HashMap<Long, Pair<StorageLocationEntity, PhotoStorage>>()

    /** Speicher zu einer Speicherort-ID; wird neu gebaut, wenn sich der Speicherort geändert hat. */
    suspend fun storage(id: Long): PhotoStorage? {
        val loc = db.locations().get(id) ?: return null
        return cacheLock.withLock {
            cache[id]?.takeIf { it.first == loc }?.second
                ?: runCatching { factory.create(loc) }.getOrNull()?.also { cache[id] = loc to it }
        }
    }

    suspend fun forget(id: Long) = cacheLock.withLock { cache.remove(id) }

    val ratings = RatingService(db, ::storage) { storageId -> scope.launch { enqueueFlush(storageId) } }
    val library = LibraryRepository(db, { ratings.pendingPaths(it) }, ::storage)

    init {
        // Läuft ein Einlesen, hält ein Vordergrund-Dienst die App am Leben, auch wenn sie in den Hintergrund geht.
        scope.launch { library.active.collect { if (it > 0) ScanService.start(app) } }
    }

    private suspend fun enqueueFlush(storageId: Long) {
        val local = db.locations().get(storageId)?.type == StorageType.LOCAL
        RatingWorker.enqueue(app, local)
    }

    /** Wartende Bewertungen jetzt hochladen; bleibt danach etwas übrig, kümmert sich die Netz-Variante darum. */
    fun uploadNow() {
        RatingWorker.runNow(app)
        RatingWorker.enqueue(app, local = false)
    }

    fun enqueueAllFlushes() {
        RatingWorker.enqueue(app, local = true)
        RatingWorker.enqueue(app, local = false)
    }
}
