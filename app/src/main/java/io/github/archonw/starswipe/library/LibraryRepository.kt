package io.github.archonw.starswipe.library

import io.github.archonw.starswipe.db.AppDatabase
import io.github.archonw.starswipe.db.PhotoEntity
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

    private class Listener(
        val onBatch: suspend () -> Unit,
        val onProgress: suspend (Int) -> Unit,
        val onFound: suspend (Int) -> Unit,
    )

    /** Ein Einlese-Durchgang, dem sich mehrere Anfragen anschließen können. */
    private class Run(var only: Long?) {
        val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
        val result = CompletableDeferred<Map<Long, StorageException>>()
    }

    private val runLock = Any()
    private var running: Run? = null
    private var waiting: Run? = null

    /**
     * Liest alle aktivierten Speicherorte neu ein – nie zwei Scans gleichzeitig, nie auf dem Aufrufer-Thread.
     * Läuft schon ein Einlesen, das [only] abdeckt, schließt sich die Anfrage ihm an (kein zweiter Durchgang,
     * wichtig bei langsamem Netz). [rescan] = Einstellungen haben sich geändert, daher nach dem laufenden
     * noch einmal einlesen; mehrere solche Anfragen teilen sich einen Folge-Durchgang.
     * Ergebnis: Fehler je Speicherort-ID. [onBatch] läuft nach jedem gespeicherten Ordner.
     */
    suspend fun refreshAll(
        onBatch: suspend () -> Unit = {},
        onProgress: suspend (Int) -> Unit = {},
        onFound: suspend (Int) -> Unit = {},
        only: Long? = null,
        rescan: Boolean = false,
    ): Map<Long, StorageException> {
        _active.update { it + 1 }
        val listener = Listener(onBatch, onProgress, onFound)
        var run: Run? = null
        try {
            var mine = false
            run = synchronized(runLock) {
                val r = running
                if (!rescan && r != null && (r.only == null || r.only == only)) r
                else waiting?.also { w -> if (w.only != only) w.only = null }
                    ?: Run(only).also { waiting = it; mine = true }
            }
            run.listeners += listener
            if (mine) execute(run)
            val result = run.result.await()
            return if (only == null) result else result.filterKeys { it == only }
        } finally {
            run?.listeners?.remove(listener)
            _active.update { it - 1 }
        }
    }

    private suspend fun execute(run: Run) {
        try {
            withContext(Dispatchers.Default) {
                scanLock.withLock {
                    val only = synchronized(runLock) { waiting = null; running = run; run.only }
                    try {
                        run.result.complete(scanAll(run, only))
                    } finally {
                        synchronized(runLock) { if (running === run) running = null }
                    }
                }
            }
        } finally {
            // Abgebrochen, bevor es fertig war: Wartende nicht hängen lassen.
            synchronized(runLock) { if (waiting === run) waiting = null }
            if (!run.result.isCompleted) run.result.completeExceptionally(CancellationException("Einlesen abgebrochen"))
        }
    }

    private suspend fun scanAll(run: Run, only: Long?): Map<Long, StorageException> {
        val onBatch: suspend () -> Unit = { run.listeners.forEach { it.onBatch() } }
        val onProgress: suspend (Int) -> Unit = { n -> run.listeners.forEach { it.onProgress(n) } }
        val onFound: suspend (Int) -> Unit = { n -> run.listeners.forEach { it.onFound(n) } }
        kotlin.run {
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
            return failures
        }
    }
}
