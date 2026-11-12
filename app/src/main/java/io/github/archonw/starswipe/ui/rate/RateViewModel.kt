package io.github.archonw.starswipe.ui.rate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.archonw.starswipe.AppContainer
import io.github.archonw.starswipe.db.PhotoEntity
import io.github.archonw.starswipe.settings.UiSettings
import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.PreviewSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class RateViewModel(private val c: AppContainer, only: Long? = null) : ViewModel() {
    companion object {
        /** Beim Öffnen nicht neu einlesen, wenn das letzte fehlerfreie Einlesen jünger ist (Knopf „Neu einlesen“ liest immer). */
        const val REUSE_SCAN_MS = 15 * 60 * 1000L
    }

    private val core = RateCore(c.library, c.ratings, only) { c.db.locations().get(it)?.name }

    val state: StateFlow<RateState> = combine(core.state, c.ratings.pendingCount()) { s, p -> s.copy(pending = p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RateState(loading = true))
    val ui: StateFlow<UiSettings> = c.settings.ui.stateIn(viewModelScope, SharingStarted.Eagerly, UiSettings())
    val hasLocations: StateFlow<Boolean?> = c.db.locations().observeAll().map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val previews = HashMap<Pair<Long, String>, PreviewSource?>()

    init {
        viewModelScope.launch {
            core.load()
            // Einlesen im App-Bereich: läuft weiter, wenn der Bildschirm kurz verlassen wird (Einstellungen o. Ä.).
            c.scope.launch { core.refresh(maxAgeMs = REUSE_SCAN_MS) }
        }
        viewModelScope.launch { core.watch() }
    }

    fun rate(stars: Int) = viewModelScope.launch { core.rate(stars) }
    fun skip() = viewModelScope.launch { core.skip() }
    fun back() = viewModelScope.launch { core.back() }
    fun undo() = viewModelScope.launch { core.undo() }
    fun setShowRated(on: Boolean) = viewModelScope.launch { core.setShowRated(on) }
    fun refresh() = c.scope.launch { core.refresh() }
    fun clearMessage() = core.clearMessage()

    /** Bildquelle für ein Foto (kann IO brauchen, daher suspend). */
    suspend fun preview(p: PhotoEntity): PreviewSource? {
        val k = p.storageId to p.path
        previews[k]?.let { return it }
        val s = c.storage(p.storageId) ?: return null
        val src = withContext(Dispatchers.IO) {
            runCatching {
                s.previewRequest(Entry(p.path, p.name, false, p.size, Instant.ofEpochMilli(p.modified), p.etag, null, p.fileId, null))
            }.getOrNull()
        }
        previews[k] = src
        return src
    }
}
