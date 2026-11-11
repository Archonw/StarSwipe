package io.github.archonw.starswipe.storage.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.PreviewSource
import io.github.archonw.starswipe.storage.Signature
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.storage.join
import io.github.archonw.starswipe.storage.nameOf
import io.github.archonw.starswipe.storage.parentOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.Instant

/** Ordner auf dem Handy über das Storage Access Framework. */
class LocalStorage(private val context: Context, private val root: DocumentFile) : PhotoStorage {

    constructor(context: Context, treeUri: Uri) :
        this(context, DocumentFile.fromTreeUri(context, treeUri) ?: throw StorageException.Unauthorized())

    /** Pfad → Dokument aus dem letzten Auflisten; erspart findFile (listet jedes Mal den ganzen Ordner). */
    private val known = java.util.concurrent.ConcurrentHashMap<String, DocumentFile>()

    /** Ordnerfreigabe (content://…/tree/…): Auflisten mit einer Abfrage je Ordner statt mehrerer je Datei. */
    private val tree: Uri? = root.uri.takeIf { it.scheme == "content" && it.pathSegments.firstOrNull() == "tree" }
    /** Pfad → Dokument-ID innerhalb der Freigabe. */
    private val ids = java.util.concurrent.ConcurrentHashMap<String, String>().also { m ->
        tree?.let { m[""] = if (DocumentsContract.isDocumentUri(context, it)) DocumentsContract.getDocumentId(it) else DocumentsContract.getTreeDocumentId(it) }
    }

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** Dokument-ID zu einem Pfad; unbekannte Pfade werden über die Elternordner aufgelöst. */
    private fun idOf(path: String): String? {
        ids[path]?.let { return it }
        if (path.isEmpty()) return null
        val parent = parentOf(path)
        idOf(parent) ?: return null
        listTree(parent)
        return ids[path]
    }

    private fun listTree(dir: String): List<Entry> {
        val parentId = idOf(dir) ?: throw StorageException.NotFound(dir)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Entry>()
        val c = context.contentResolver.query(uri, PROJECTION, null, null, null) ?: throw StorageException.NotFound(dir)
        c.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val p = join(dir, name)
                ids[p] = id
                out += Entry(p, name, isDir, if (it.isNull(3)) 0 else it.getLong(3),
                    Instant.ofEpochMilli(if (it.isNull(4)) 0 else it.getLong(4)), null, if (isDir) null else mime, null, null)
            }
        }
        return out
    }

    private fun resolve(path: String): DocumentFile? {
        if (tree != null) {
            val id = idOf(path) ?: return null
            return DocumentFile.fromTreeUri(context, docUri(id))?.takeIf { it.exists() } ?: run { ids.remove(path); null }
        }
        known[path]?.let { if (it.exists()) return it else known.remove(path) }
        var cur = root
        for (seg in path.split('/').filter { it.isNotEmpty() }) cur = cur.findFile(seg) ?: return null
        return cur
    }

    private fun entry(path: String, d: DocumentFile) = Entry(path, d.name ?: nameOf(path), d.isDirectory,
        d.length(), Instant.ofEpochMilli(d.lastModified()), null, d.type, null, null)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (e: SecurityException) { throw StorageException.Unauthorized() }
        catch (e: java.io.IOException) { throw StorageException.Other("local storage: ${e.message}") }
    }

    override suspend fun list(dir: String): List<Entry> = io {
        if (tree != null) return@io listTree(dir)
        val d = resolve(dir)?.takeIf { it.isDirectory } ?: throw StorageException.NotFound(dir)
        d.listFiles().map { f -> val p = join(dir, f.name ?: ""); known[p] = f; entry(p, f) }
    }

    override suspend fun read(path: String, maxBytes: Long?): ByteArray = io {
        val uri = if (tree != null) idOf(path)?.let(::docUri) ?: throw StorageException.NotFound(path)
            else resolve(path)?.takeIf { it.isFile }?.uri ?: throw StorageException.NotFound(path)
        val inp = try { context.contentResolver.openInputStream(uri) } catch (e: FileNotFoundException) { null }
            ?: throw StorageException.NotFound(path)
        inp.use {
            if (maxBytes == null) it.readBytes()
            else {
                val buf = ByteArray(maxBytes.toInt()); var off = 0
                while (off < buf.size) { val r = it.read(buf, off, buf.size - off); if (r < 0) break; off += r }
                buf.copyOf(off)
            }
        }
    }

    override suspend fun stat(path: String): Entry? = io { resolve(path)?.takeIf { it.isFile }?.let { entry(path, it) } }

    override suspend fun write(path: String, data: ByteArray, expected: Signature?): Signature = io {
        val cur = resolve(path)?.takeIf { it.isFile }
        if (expected == null && cur != null) throw StorageException.Conflict(path)
        if (expected != null && (cur == null || !expected.matches(entry(path, cur)))) throw StorageException.Conflict(path)
        val target = cur ?: run {
            val parent = resolve(parentOf(path))?.takeIf { it.isDirectory } ?: throw StorageException.NotFound(parentOf(path))
            val name = nameOf(path)
            val created = parent.createFile("application/octet-stream", name) ?: throw StorageException.Other("create failed: $path")
            // Manche Provider hängen eine Endung an – dann zurückbenennen
            if (created.name != name && !created.renameTo(name)) throw StorageException.Other("wrong name: ${created.name}")
            (parent.findFile(name) ?: created).also {
                known[path] = it
                if (tree != null) ids[path] = DocumentsContract.getDocumentId(it.uri)
            }
        }
        (context.contentResolver.openOutputStream(target.uri, "wt") ?: throw StorageException.Other("not writable: $path"))
            .use { it.write(data) }
        entry(path, resolve(path)!!).signature
    }

    override suspend fun delete(path: String, expected: Signature?) = io {
        val cur = resolve(path)?.takeIf { it.isFile } ?: throw StorageException.NotFound(path)
        if (expected != null && !expected.matches(entry(path, cur))) throw StorageException.Conflict(path)
        if (!cur.delete()) throw StorageException.Other("delete failed: $path")
        known.remove(path); ids.remove(path)
        Unit
    }

    override fun previewRequest(entry: Entry): PreviewSource =
        (if (tree != null) ids[entry.path]?.let { PreviewSource.ContentUri(docUri(it).toString()) } else null)
            ?: resolve(entry.path)?.let { PreviewSource.ContentUri(it.uri.toString()) }
            ?: PreviewSource.Bytes("local:${root.uri}/${entry.path}") { read(entry.path) }
}

private val PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
)
