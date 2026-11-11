package io.github.archonw.starswipe.storage

/** Adressen, bei denen unverschlüsseltes HTTP erlaubt ist (Heimnetz). */
object HomeNetwork {
    fun allowsCleartext(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.endsWith(".local") || h == "fritz.box" || h.endsWith(".fritz.box")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }
}
