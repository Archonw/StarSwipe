package io.github.archonw.starswipe.library

import io.github.archonw.starswipe.db.AppDatabase
import io.github.archonw.starswipe.db.PhotoEntity
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    /** Zeitpunkt des letzten fehlerfreien Einlesens je Speicherort (nur solange die App läuft). */
    private val lastOk = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    internal var clock: () -> Long = System::currentTimeMillis
    private var running: Run? = null
    private var waiting: Run? = null

    /**
     * Liest alle aktivierten Speicherorte neu ein – nie zwei Scans gleichzeitig, nie auf dem Aufrufer-Thread.
     * Läuft schon ein Einlesen, das [only] abdeckt, schließt sich die Anfrage ihm an (kein zweiter Durchgang,
     * wichtig bei langsamem Netz). [rescan] = Einstellungen haben sich geändert, daher nach dem laufenden
     * noch einmal einlesen; mehrere solche Anfragen teilen sich einen Folge-Durchgang.
     * [maxAgeMs]: wurden alle betroffenen Speicherorte vor höchstens so langer Zeit fehlerfrei eingelesen,
     * entfällt das Einlesen (erneutes Öffnen des Bewertens); null = immer einlesen.
     * [skipIfCached]: nur Speicherorte einlesen, von denen noch nichts gespeichert ist (automatisches Einlesen aus).
     * Ergebnis: Fehler je Speicherort-ID. [onBatch] läuft nach jedem gespeicherten Ordner.
     */
    suspend fun refreshAll(
        onBatch: suspend () -> Unit = {},
        onProgress: suspend (Int) -> Unit = {},
        onFound: suspend (Int) -> Unit = {},
        only: Long? = null,
        rescan: Boolean = false,
        maxAgeMs: Long? = null,
        skipIfCached: Boolean = false,
    ): Map<Long, StorageException> {
        if (maxAgeMs != null && !rescan && isFresh(only, maxAgeMs)) return emptyMap()
        if (skipIfCached && !rescan) {
            val missing = inScope(only).filter { db.photos().countFor(it) == 0 }
            if (missing.isEmpty()) return emptyMap()
            // Nur die noch leeren Speicherorte einlesen – große, schon eingelesene bleiben unberührt.
            if (only == null) return buildMap { for (id in missing) putAll(refreshAll(onBatch, onProgress, onFound, id)) }
        }
        val listener = Listener(onBatch, onProgress, onFound)
        var mine = false
        // Erst anschließen bzw. einreihen, dann zählen: [active] > 0 heißt „hängt an einem Durchgang“.
        val run = synchronized(runLock) {
            val r = running
            (if (!rescan && r != null && (r.only == null || r.only == only)) r
            else waiting?.also { w -> if (w.only != only) w.only = null }
                ?: Run(only).also { waiting = it; mine = true }).also { it.listeners += listener }
        }
        _active.update { it + 1 }
        try {
            if (mine) execute(run)
            val result = run.result.await()
            return if (only == null) result else result.filterKeys { it == only }
        } finally {
            run.listeners.remove(listener)
            _active.update { it - 1 }
        }
    }

    private suspend fun isFresh(only: Long?, maxAgeMs: Long): Boolean {
        if (synchronized(runLock) { running != null || waiting != null }) return false
        val ids = inScope(only)
        val now = clock()
        return ids.isNotEmpty() && ids.all { id -> lastOk[id]?.let { now - it < maxAgeMs } == true }
    }

    // Speicherort, der gerade dran ist, und sein Einlese-Auftrag (zum gezielten Abbrechen). Ein Stopp, der kommt,
    // bevor der Auftrag existiert, wird vorgemerkt – sonst ginge er im Zeitfenster dazwischen verloren.
    private val stopLock = Any()
    private var currentId: Long? = null
    private var currentJob: Job? = null
    private var stopRequested = false

    /** Laufendes Einlesen dieses Speicherorts abbrechen (ausgeschaltet, entfernt, Ordner geändert); andere laufen weiter. */
    fun stopScanOf(id: Long) {
        lastOk.remove(id)
        synchronized(stopLock) { if (currentId == id) { stopRequested = true; currentJob?.cancel() } }
    }

    /**
     * Ordnerauswahl wurde gespeichert: laufendes Einlesen dieses Speicherorts abbrechen und Fotos außerhalb
     * der neuen Ordner sofort entfernen. Danach `refreshAll(only = id, rescan = true)` aufrufen.
     */
    suspend fun foldersChanged(id: Long) {
        stopScanOf(id)
        val folders = db.locations().get(id)?.folders?.map { it.trim('/') } ?: return
        if ("" in folders) return // Wurzel gewählt: alles bleibt
        val outside = db.photos().byStorage(id).map { it.path }.filter { p -> folders.none { f -> p.startsWith("$f/") } }
        outside.chunked(500).forEach { db.photos().deletePaths(id, it) }
    }

    private suspend fun inScope(only: Long?): List<Long> =
        db.locations().all().filter { it.enabled && (only == null || it.id == only) }.map { it.id }

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
            for (id in inScope(only)) {
                synchronized(stopLock) { currentId = id; currentJob = null; stopRequested = false }
                // Erst jetzt lesen: Ordner können sich geändert haben, während andere Speicherorte dran waren.
                val loc = db.locations().get(id)?.takeIf { it.enabled } ?: continue
                if (loc.folders.isEmpty()) {
                    failures[loc.id] = StorageException.NoFolders(); continue
                }
                val s = storages(loc.id)
                if (s == null) { failures[loc.id] = StorageException.Unauthorized(); continue } // z. B. Passwort fehlt
                _scanning.value = ScanStatus(loc.id)
                lastOk.remove(loc.id)
                try {
                    var last = 0
                    var lastFound = 0
                    val stopped = try {
                        coroutineScope {
                            val job = launch {
                                scanner.scan(loc, s, { pendingPaths(loc.id) }, onBatch,
                                    onProgress = { n -> last = n; _scanning.value = ScanStatus(loc.id, lastFound, n); onProgress(before + n) },
                                    onFound = { n -> lastFound = n; _scanning.value = ScanStatus(loc.id, n, last); onFound(foundBefore + n) })
                            }
                            synchronized(stopLock) { currentJob = job; if (stopRequested) job.cancel() }
                            job.join()
                            job.isCancelled
                        }
                    } finally { before += last; foundBefore += lastFound }
                    // Gestoppt (Ordner geändert, ausgeschaltet, entfernt): kein Fehler, der Folge-Durchgang übernimmt.
                    if (!stopped) lastOk[loc.id] = clock()
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
            synchronized(stopLock) { currentId = null; currentJob = null }
            return failures
        }
    }
}
