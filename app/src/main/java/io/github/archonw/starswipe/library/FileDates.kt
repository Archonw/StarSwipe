package io.github.archonw.starswipe.library

import java.time.DateTimeException
import java.time.LocalDateTime

/** Aufnahmedatum aus üblichen Handy-Dateinamen (PXL_…, IMG_…, 2024…_…, Screenshot_…, signal-…), ohne die Datei zu lesen. */
object FileDates {
    private val RX = Regex("""(?<!\d)((?:19|20)\d{2})[-_]?(\d{2})[-_]?(\d{2})(?:[-_ T](\d{2})[-_.:]?(\d{2})[-_.:]?(\d{2}))?""")

    fun fromName(name: String): LocalDateTime? {
        for (m in RX.findAll(name)) {
            val g = m.groupValues
            val hasTime = g[4].isNotEmpty()
            if (!hasTime && name.getOrNull(m.range.last + 1)?.isDigit() == true) continue
            try {
                return LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(),
                    if (hasTime) g[4].toInt() else 0, if (hasTime) g[5].toInt() else 0, if (hasTime) g[6].toInt() else 0)
            } catch (_: DateTimeException) { continue }
        }
        return null
    }
}
