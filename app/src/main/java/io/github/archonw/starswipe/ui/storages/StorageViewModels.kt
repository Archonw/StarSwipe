package io.github.archonw.starswipe.ui.storages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.archonw.starswipe.AppContainer
import io.github.archonw.starswipe.db.StorageLocationEntity
import io.github.archonw.starswipe.db.StorageType
import io.github.archonw.starswipe.settings.NextcloudCfg
import io.github.archonw.starswipe.settings.StorageFactory
import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.dav.NcCredentials
import io.github.archonw.starswipe.R
import io.github.archonw.starswipe.ui.UiText
import io.github.archonw.starswipe.ui.uiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StorageListViewModel(private val c: AppContainer) : ViewModel() {
    val locations: StateFlow<List<StorageLocationEntity>> =
        c.db.locations().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Unbewertete Fotos je Speicherort (Stand des letzten Einlesens). */
    val unrated: StateFlow<Map<Long, Int>> = c.db.photos().unratedCounts()
        .map { l -> l.associate { it.storageId to it.count } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Speicherort, der gerade eingelesen wird (auch wenn das Einlesen woanders gestartet wurde). */
    val scanning = c.library.scanning

    fun setEnabled(l: StorageLocationEntity, on: Boolean) = viewModelScope.launch { c.db.locations().update(l.copy(enabled = on)) }
    fun setOneStar(l: StorageLocationEntity, on: Boolean) = viewModelScope.launch { c.db.locations().update(l.copy(oneStarIsUnrated = on)) }

    fun remove(l: StorageLocationEntity) = viewModelScope.launch {
        if (l.type == StorageType.NEXTCLOUD) runCatching {
            val cfg = StorageFactory.json.decodeFromString(NextcloudCfg.serializer(), l.config)
            val pw = c.secrets.get(StorageFactory.secretKey(l.id))
            if (pw != null) c.nextcloudLogin.revoke(NcCredentials(cfg.server, cfg.loginName, pw, cfg.userId))
        }
        c.db.photos().deleteByStorage(l.id)
        c.db.pending().deleteByStorage(l.id)
        c.db.locations().delete(l.id)
        c.secrets.remove(StorageFactory.secretKey(l.id))
        c.forget(l.id)
    }
}

class AddStorageViewModel(private val c: AppContainer) : ViewModel() {
    val form = MutableStateFlow(StorageForm())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<UiText?>(null)
    val saved = MutableStateFlow<Pair<Long, StorageType>?>(null)
    private var job: Job? = null

    private val core = AddStorageCore(c.db.locations(), c.secrets, c.nextcloudLogin) { loc, secret -> c.factory.create(loc, secret) }

    fun update(f: StorageForm) { form.value = f; error.value = null }

    private fun run(block: suspend () -> ConnectResult) {
        job?.cancel()
        job = viewModelScope.launch {
            busy.value = true; error.value = null
            when (val r = block()) {
                is ConnectResult.Saved -> saved.value = r.id to form.value.type
                is ConnectResult.Error -> error.value = r.message
            }
            busy.value = false
        }
    }

    fun connect() = run { core.connect(form.value) }
    fun loginNextcloud(openBrowser: (String) -> Unit) = run { core.loginNextcloud(form.value.url, form.value.name, openBrowser) }
    fun cancel() { job?.cancel(); busy.value = false }

    fun saveLocal(uri: String, name: String) = viewModelScope.launch {
        val id = core.saveLocal(uri, form.value.name.ifBlank { name })
        saved.value = id to StorageType.LOCAL
        c.scope.launch { c.library.refreshAll() }
    }
}

class FolderPickerViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val dir = MutableStateFlow("")
    val folders = MutableStateFlow<List<Entry>>(emptyList())
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val error = MutableStateFlow<UiText?>(null)
    val busy = MutableStateFlow(false)
    val done = MutableStateFlow(false)
    private var core: FolderPickerCore? = null

    init {
        viewModelScope.launch {
            val s = c.storage(id)
            if (s == null) { error.value = uiText(R.string.storage_unavailable); return@launch }
            core = FolderPickerCore(id, c.db.locations(), s).also { it.load(); selected.value = it.selected }
            open("")
        }
    }

    fun open(path: String) = viewModelScope.launch {
        val k = core ?: return@launch
        busy.value = true; error.value = null
        try { folders.value = k.open(path); dir.value = path } catch (e: Exception) { error.value = AddStorageCore.message(e) }
        busy.value = false
    }

    fun up() = open(dir.value.substringBeforeLast('/', ""))
    fun toggle(path: String) { core?.toggle(path); selected.value = core?.selected ?: emptySet() }

    fun save() = viewModelScope.launch {
        core?.save(current = dir.value)
        c.scope.launch { c.library.refreshAll() }
        done.value = true
    }
}
