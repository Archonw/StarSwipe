package io.github.archonw.starswipe.library

import io.github.archonw.starswipe.db.AppDatabase
import io.github.archonw.starswipe.db.PhotoEntity
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Welcher Speicherort gerade eingelesen wird und wie weit (Zahlen nur für diesen Speicherort). */
data class ScanStatus(val storageId: Long, val found: Int = 0, val scanned: Int = 0)

class LibraryRepository(
    private val db: AppDatabase,
    private val pendingPaths: suspend (Long) -> Set<String> = { emptySet() },
    private val storages: suspend (Long) -> PhotoStorage?,
) {
    constructor(db: AppDatabase, storages: suspend (Long) -> PhotoStorage?) : this(db, { emptySet() }, storages)

    private val scanner = Scanner(db)
    private val scanLock = Mutex()
    private val _scanning = MutableStateFlow<ScanStatus?>(null)
    /** Laufendes Einlesen, null = keines. */
    val scanning: StateFlow<ScanStatus?> = _scanning
    private val _active = MutableStateFlow(0)
    /** Laufende plus wartende Einlese-Aufträge; > 0 hält den Vordergrund-Dienst am Leben. */
    val active: StateFlow<Int> = _active

    /** [only]: nur dieser Speicherort, null = alle aktiven. */
    fun queue(showRated: Boolean, only: Long? = null): Flow<List<PhotoEntity>> =
        db.photos().queue(showRated, only ?: io.github.archonw.starswipe.db.PhotoDao.ALL)

    /**
     * Liest alle aktivierten Speicherorte neu ein – nie zwei Scans gleichzeitig, nie auf dem Aufrufer-Thread.
     * Ergebnis: Fehler je Speicherort-ID. [onBatch] läuft nach jedem gespeicherten Ordner.
     */
    suspend fun refreshAll(
        onBatch: suspend () -> Unit = {},
        onProgress: suspend (Int) -> Unit = {},
        onFound: suspend (Int) -> Unit = {},
        only: Long? = null,
    ): Map<Long, StorageException> {
        _active.update { it + 1 }
        try { return scanAll(onBatch, onProgress, onFound, only) } finally { _active.update { it - 1 } }
    }

    private suspend fun scanAll(
        onBatch: suspend () -> Unit,
        onProgress: suspend (Int) -> Unit,
        onFound: suspend (Int) -> Unit,
        only: Long?,
    ): Map<Long, StorageException> = withContext(Dispatchers.Default) {
        scanLock.withLock {
            val failures = mutableMapOf<Long, StorageException>()
            var before = 0
            var foundBefore = 0
            for (loc in db.locations().all().filter { it.enabled && (only == null || it.id == only) }) {
                if (loc.folders.isEmpty()) {
                    failures[loc.id] = StorageException.NoFolders(); continue
                }
                val s = storages(loc.id)
                if (s == null) { failures[loc.id] = StorageException.Unauthorized(); continue } // z. B. Passwort fehlt
                _scanning.value = ScanStatus(loc.id)
                try {
                    var last = 0
                    var lastFound = 0
                    try {
                        scanner.scan(loc, s, { pendingPaths(loc.id) }, onBatch,
                            onProgress = { n -> last = n; _scanning.value = ScanStatus(loc.id, lastFound, n); onProgress(before + n) },
                            onFound = { n -> lastFound = n; _scanning.value = ScanStatus(loc.id, n, last); onFound(foundBefore + n) })
                    } finally { before += last; foundBefore += lastFound }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StorageException) {
                    failures[loc.id] = e
                } catch (e: Exception) {
                    failures[loc.id] = StorageException.Other(e.message ?: e.toString())
                } finally {
                    _scanning.value = null
                }
            }
            failures
        }
    }
}
