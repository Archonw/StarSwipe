package io.github.archonw.starswipe.ui.rate

import io.github.archonw.starswipe.R
import io.github.archonw.starswipe.db.PhotoEntity
import io.github.archonw.starswipe.ui.UiText
import io.github.archonw.starswipe.ui.uiText
import io.github.archonw.starswipe.library.LibraryRepository
import io.github.archonw.starswipe.rating.RatingService
import io.github.archonw.starswipe.rating.UndoToken
import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RateState(
    val current: PhotoEntity? = null,
    val upcoming: List<PhotoEntity> = emptyList(),
    val remaining: Int = 0,
    val showRated: Boolean = false,
    val pending: Int = 0,
    val canUndo: Boolean = false,
    val canGoBack: Boolean = false,
    val storageName: String = "",
    val message: UiText? = null,
    val loading: Boolean = false,
    /** Beim laufenden Einlesen: bisher geprüfte Fotos. */
    val scanned: Int = 0,
    /** Beim laufenden Einlesen: bisher gefundene Fotos (noch nicht alle geprüft). */
    val found: Int = 0,
)

/** Ein Ordner der Warteschlange zum Hinspringen. [label]: mit Speicherort-Name, falls mehrere beteiligt sind. */
data class FolderJump(val storageId: Long, val folder: String, val label: String, val count: Int, val current: Boolean)

/**
 * Ablauf beim Bewerten. Arbeitet auf einem Schnappschuss der Warteschlange, damit das
 * gerade bewertete Foto nicht durch Datenbank-Updates „wegspringt“. Alle Zustandsänderungen
 * laufen unter einer Sperre (Doppeltipp, Einlesen im Hintergrund).
 */
class RateCore(
    private val library: LibraryRepository,
    private val ratings: RatingService,
    /** Nur diesen Speicherort bewerten; null = alle aktiven. */
    private val only: Long? = null,
    private val names: suspend (Long) -> String?,
) {
    companion object { const val UNDO_DEPTH = 100; const val PREFETCH = 5 }

    private val _state = MutableStateFlow(RateState())
    val state: StateFlow<RateState> = _state
    private val lock = Mutex()

    private var snapshot: List<PhotoEntity> = emptyList()
    private var index = 0
    private var showRated = false
    private var failing: Map<Long, StorageException> = emptyMap()
    private var message: UiText? = null
    private var loading = false
    private var scanned = 0
    private var found = 0
    private val undoStack = ArrayDeque<Pair<Int, UndoToken>>()
    private val nameCache = HashMap<Long, String>()
    var lastRated: UndoToken? = null; private set

    /** Nur den Cache laden (schnell, ohne Netz). */
    suspend fun load() = lock.withLock { if (snapshot.isEmpty()) resnapshot(); emit() }

    /** Solange noch nichts angezeigt wurde: neue Fotos aus der Datenbank übernehmen (z. B. nach dem Anlegen eines Speicherorts). */
    suspend fun watch() {
        library.queue(false, only).collect { lock.withLock { reconcile(); emit() } }
    }

    /**
     * Speicherorte neu einlesen; solange noch nichts angezeigt wird, erscheinen Fotos ordnerweise.
     * [maxAgeMs]: kürzlich fehlerfrei Eingelesenes nicht erneut einlesen (beim Öffnen), null = immer (Knopf).
     * [skipIfCached]: automatisches Einlesen ist aus – nur noch nie Eingelesenes einlesen.
     */
    suspend fun refresh(maxAgeMs: Long? = null, skipIfCached: Boolean = false) {
        lock.withLock { loading = true; scanned = 0; found = 0; emit() }
        val result = library.refreshAll(only = only, maxAgeMs = maxAgeMs, skipIfCached = skipIfCached, onBatch = {
            lock.withLock { if (snapshot.getOrNull(index) == null && index == 0 && undoStack.isEmpty()) { resnapshot(); emit() } }
        }, onProgress = { n -> lock.withLock { scanned = n; emit() } },
            onFound = { n -> lock.withLock { found = n; emit() } })
        lock.withLock {
            failing = result
            message = describe(result)
            if (index == 0 && undoStack.isEmpty()) resnapshot() else merge()
            loading = false
            emit()
        }
    }

    private suspend fun describe(f: Map<Long, StorageException>): UiText? {
        if (f.isEmpty()) return null
        val lines = mutableListOf<UiText>()
        for ((id, e) in f) {
            val n: Any = names(id) ?: uiText(R.string.storage_fallback)
            lines += when (e) {
                is StorageException.Unauthorized -> uiText(R.string.err_rate_unauthorized, n)
                is StorageException.Unreachable -> uiText(R.string.err_rate_unreachable, n,
                    e.cause?.let { c -> " (${c.javaClass.simpleName}${c.message?.let { m -> ": ${m.take(80)}" } ?: ""})" } ?: "")
                is StorageException.NoFolders -> uiText(R.string.err_rate_other, n, uiText(R.string.err_no_folders))
                else -> uiText(R.string.err_rate_other, n, e.message ?: e.toString())
            }
        }
        return UiText.Lines(lines)
    }

    private suspend fun queue() = library.queue(showRated, only).first().filter { it.storageId !in failing }

    private suspend fun resnapshot() { snapshot = queue(); index = 0; undoStack.clear() }

    /** Nach einem Einlesen während des Bewertens: Neues hinten anhängen, Unerreichbares danach weglassen. */
    private suspend fun merge() {
        prune()
        val fresh = queue()
        val known = snapshot.map { it.storageId to it.path }.toHashSet()
        val head = snapshot.take(index + 1)
        val tail = snapshot.drop(index + 1).filter { it.storageId !in failing }
        snapshot = head + tail + fresh.filter { (it.storageId to it.path) !in known }
    }

    suspend fun rate(stars: Int) = lock.withLock {
        val cur = snapshot.getOrNull(index) ?: return@withLock
        val token = ratings.rate(cur, stars)
        snapshot = snapshot.toMutableList().also { it[index] = cur.copy(rating = stars) }
        undoStack.addLast(index to token)
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        lastRated = token
        index++
        emit()
    }

    /** Stapel mit der Datenbank abgleichen (Speicherort entfernt/hinzugefügt, Dateien gelöscht). */
    suspend fun sync() = lock.withLock { reconcile(); emit() }

    private suspend fun reconcile() {
        if (snapshot.isEmpty() && index == 0 && undoStack.isEmpty()) { resnapshot(); return }
        prune(); appendFresh()
    }

    /** Entfernt Fotos, die es in der Datenbank nicht mehr gibt; Position und Rückgängig-Liste bleiben stimmig. */
    private suspend fun prune() {
        val alive = library.queue(true, only).first().map { it.storageId to it.path }.toHashSet()
        val newIndexOf = HashMap<Int, Int>()
        val kept = ArrayList<PhotoEntity>()
        snapshot.forEachIndexed { i, p -> if ((p.storageId to p.path) in alive) { newIndexOf[i] = kept.size; kept += p } }
        if (kept.size == snapshot.size) return
        index = snapshot.indices.count { it < index && it in newIndexOf }
        val undo = undoStack.mapNotNull { (i, t) -> newIndexOf[i]?.let { it to t } }
        undoStack.clear(); undoStack.addAll(undo)
        snapshot = kept
    }

    private suspend fun appendFresh() {
        val known = snapshot.map { it.storageId to it.path }.toHashSet()
        snapshot = snapshot + queue().filter { (it.storageId to it.path) !in known }
    }

    /** Ordner in Reihenfolge der Warteschlange (neueste zuerst) mit Anzahl Fotos darin. */
    suspend fun folders(): List<FolderJump> = lock.withLock {
        val cur = snapshot.getOrNull(index)
        val groups = LinkedHashMap<Pair<Long, String>, Int>()
        snapshot.forEach { groups.merge(it.storageId to it.folder, 1, Int::plus) }
        val multi = groups.keys.map { it.first }.distinct().size > 1
        // Gemeinsamen Pfadanfang weglassen (lange Pfade wie Bilder/Alle/Jan/2026/…), letzter Teil bleibt immer.
        val parts = groups.keys.map { it.second.split('/').filter(String::isNotEmpty) }
        val common = if (multi || parts.size < 2) 0 else {
            val max = parts.minOf { it.size } - 1
            (0 until max.coerceAtLeast(0)).takeWhile { i -> parts.all { it[i] == parts[0][i] } }.count()
        }
        groups.map { (k, n) ->
            val segs = k.second.split('/').filter(String::isNotEmpty)
            val drop = (common - 1).coerceAtLeast(0) // einen übergeordneten Teil zur Orientierung behalten (…/2026/11)
            val path = if (drop > 0) "…/" + segs.drop(drop).joinToString("/") else k.second.ifEmpty { "/" }
            val label = if (multi) "${nameCache[k.first] ?: names(k.first)?.also { nameCache[k.first] = it } ?: ""} · $path" else path
            FolderJump(k.first, k.second, label, n, cur != null && cur.storageId == k.first && cur.folder == k.second)
        }
    }

    /** Zum ersten in dieser Sitzung noch nicht bewerteten Foto des Ordners springen (sonst zum ersten des Ordners). */
    suspend fun jumpTo(f: FolderJump) = lock.withLock {
        val ratedHere = undoStack.map { it.first }.toHashSet()
        val inFolder = snapshot.indices.filter { snapshot[it].storageId == f.storageId && snapshot[it].folder == f.folder }
        index = inFolder.firstOrNull { it !in ratedHere } ?: inFolder.firstOrNull() ?: index
        emit()
    }

    suspend fun skip() = lock.withLock { if (index < snapshot.size) index++; emit() }
    suspend fun back() = lock.withLock { if (index > 0) index--; emit() }

    suspend fun undo() {
        val token = lock.withLock {
            val (i, token) = undoStack.removeLastOrNull() ?: return
            snapshot = snapshot.toMutableList().also { it[i] = it[i].copy(rating = token.previousRating) }
            index = i
            emit()
            token
        }
        ratings.undo(token) // kann warten, bis ein laufender Schreibvorgang fertig ist
    }

    suspend fun setShowRated(on: Boolean) = lock.withLock { showRated = on; resnapshot(); emit() }

    fun clearMessage() { message = null; _state.value = _state.value.copy(message = null) }

    private suspend fun emit() {
        val cur = snapshot.getOrNull(index)
        val name = cur?.let { nameCache[it.storageId] ?: names(it.storageId)?.also { n -> nameCache[it.storageId] = n } } ?: ""
        _state.value = RateState(
            current = cur,
            upcoming = snapshot.drop(index + 1).take(PREFETCH),
            remaining = (snapshot.size - index).coerceAtLeast(0),
            showRated = showRated,
            pending = _state.value.pending,
            canUndo = undoStack.isNotEmpty(),
            canGoBack = index > 0,
            storageName = name,
            message = message,
            loading = loading,
            scanned = scanned,
            found = found,
        )
    }
}
