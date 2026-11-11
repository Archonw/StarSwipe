package io.github.archonw.starswipe.exif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class ExifDateTest {
    private val head = javaClass.getResource("/exif/sample.jpg")!!.readBytes()

    @Test fun readsJpegDate() = assertEquals(LocalDateTime.of(2018, 3, 30, 16, 32, 44), ExifDate.read(head))
    @Test fun garbageReturnsNull() = assertNull(ExifDate.read(ByteArray(100)))
    @Test fun truncatedReturnsNull() = assertNull(ExifDate.read(head.copyOf(20)))
}
