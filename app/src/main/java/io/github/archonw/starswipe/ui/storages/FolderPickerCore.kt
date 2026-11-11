package io.github.archonw.starswipe.ui.storages

import io.github.archonw.starswipe.db.StorageLocationDao
import io.github.archonw.starswipe.library.PhotoRules
import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.PhotoStorage

/** Ordnerauswahl eines Speicherorts, ohne Android-UI. */
class FolderPickerCore(private val id: Long, private val dao: StorageLocationDao, private val storage: PhotoStorage) {
    var selected: Set<String> = emptySet(); private set

    suspend fun load() { selected = dao.get(id)?.folders?.toSet() ?: emptySet() }

    suspend fun open(dir: String): List<Entry> =
        storage.list(dir).filter { it.isDir && !PhotoRules.skip(it) }.sortedBy { it.name.lowercase() }

    fun toggle(path: String) { selected = if (path in selected) selected - path else selected + path }

    suspend fun save(current: String = "") {
        val loc = dao.get(id) ?: return
        // Nichts angehakt (z. B. Ordner ohne Unterordner): den gerade offenen Ordner nehmen.
        dao.update(loc.copy(folders = selected.ifEmpty { setOf(current) }.sorted()))
    }
}
