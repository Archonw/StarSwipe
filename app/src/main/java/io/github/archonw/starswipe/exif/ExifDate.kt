package io.github.archonw.starswipe.exif

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Aufnahmedatum aus dem Anfang einer Bilddatei. */
object ExifDate {
    const val HEAD_BYTES = 131072
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun read(head: ByteArray): LocalDateTime? = try {
        val exif = ExifInterface(ByteArrayInputStream(head))
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        raw?.trim()?.take(19)?.let { LocalDateTime.parse(it, FORMAT) }
    } catch (_: Exception) {
        null
    }
}
