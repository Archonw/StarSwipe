package io.github.archonw.starswipe.storage.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SmbReadTest {
    private val file = ByteArray(1000) { it.toByte() }
    private val requested = mutableListOf<Int>()

    /** Simuliert File.read(buf, fileOffset, bufOffset, len): liefert höchstens [chunk] Bytes, -1 am Dateiende. */
    private fun reader(chunk: Int) = { buf: ByteArray, fileOffset: Long, bufOffset: Int, len: Int ->
        requested += len
        if (fileOffset >= file.size) -1 else {
            val n = minOf(len, chunk, file.size - fileOffset.toInt())
            System.arraycopy(file, fileOffset.toInt(), buf, bufOffset, n); n
        }
    }

    @Test fun readsExactlyMaxBytesInPieces() {
        val out = readHead(300, reader(128))
        assertArrayEquals(file.copyOf(300), out)
        assertEquals(listOf(300, 172, 44), requested) // nie mehr anfordern als noch fehlt
    }

    @Test fun shortFileStopsAtEnd() {
        val out = readHead(5000, reader(4096))
        assertArrayEquals(file, out)
    }
}
