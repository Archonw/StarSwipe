package io.github.archonw.starswipe.library

import io.github.archonw.starswipe.storage.Entry

object PhotoRules {
    val IMAGE_EXT = setOf("jpg", "jpeg", "heic", "heif", "png", "webp", "dng", "cr2", "nef", "arw", "orf", "rw2")
    private val SKIP_DIRS = setOf(".trash", "@eadir", "#recycle", "#snapshot")

    fun isImage(e: Entry): Boolean {
        if (e.isDir) return false
        val ext = e.name.substringAfterLast('.', "").lowercase()
        if (ext == "xmp") return false
        return ext in IMAGE_EXT || e.contentType?.startsWith("image/") == true
    }

    fun skip(e: Entry): Boolean = e.name.startsWith(".") || (e.isDir && e.name.lowercase() in SKIP_DIRS)

    /** Foto → Sidecar (case-insensitiv; `Foto.jpg.xmp` vor `Foto.xmp`). */
    fun pairSidecars(entries: List<Entry>): Map<Entry, Entry?> {
        val xmps = entries.filter { !it.isDir && it.name.lowercase().endsWith(".xmp") }.associateBy { it.name.lowercase() }
        val out = LinkedHashMap<Entry, Entry?>()
        for (e in entries.filter(::isImage)) {
            val n = e.name.lowercase()
            out[e] = xmps["$n.xmp"] ?: xmps["${n.substringBeforeLast('.')}.xmp"]
        }
        return out
    }

    fun sidecarPathFor(photoPath: String): String = "$photoPath.xmp"

    fun isUnrated(rating: Int?, oneStarIsUnrated: Boolean): Boolean =
        rating == null || (oneStarIsUnrated && rating == 1)
}
