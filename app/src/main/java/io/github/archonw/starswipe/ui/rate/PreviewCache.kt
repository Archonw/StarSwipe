package io.github.archonw.starswipe.ui.rate

import java.io.File
import java.security.MessageDigest

/**
 * Vorschaubilder dauerhaft auf dem Handy (überlebt App-Neustarts). Der Schlüssel enthält ETag bzw.
 * Änderungszeit, eine geänderte Datei bekommt also automatisch einen neuen Eintrag.
 * Über [maxBytes] werden die am längsten nicht gezeigten Bilder gelöscht.
 */
class PreviewCache(private val dir: File, private val maxBytes: Long) {
    init { dir.mkdirs() }

    private fun file(key: String): File {
        val hex = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hex)
    }

    fun get(key: String): File? = file(key).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        val f = file(key)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { tmp.delete(); return }
        trim()
    }

    private fun trim() {
        val files = dir.listFiles { x -> x.isFile && !x.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
