package io.github.archonw.starswipe.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class FileDatesTest {
    private fun d(y: Int, mo: Int, da: Int, h: Int = 0, mi: Int = 0, s: Int = 0) = LocalDateTime.of(y, mo, da, h, mi, s)

    @Test fun pixel() = assertEquals(d(2026, 11, 8, 13, 17, 3), FileDates.fromName("PXL_20261108_131703921.jpg"))
    @Test fun samsung() = assertEquals(d(2024, 1, 3, 12, 5, 12), FileDates.fromName("20240103_120512.jpg"))
    @Test fun img() = assertEquals(d(2019, 7, 1, 8, 9, 10), FileDates.fromName("IMG_20190701_080910.jpg"))
    @Test fun whatsappDateOnly() = assertEquals(d(2023, 5, 17), FileDates.fromName("IMG-20230517-WA0003.jpg"))
    @Test fun screenshot() = assertEquals(d(2025, 2, 28, 21, 4, 59), FileDates.fromName("Screenshot_20250228-210459.png"))
    @Test fun signal() = assertEquals(d(2022, 12, 24, 18, 30, 0), FileDates.fromName("signal-2022-12-24-18-30-00-123.jpg"))
    @Test fun noDate() = assertNull(FileDates.fromName("IMAG1158.jpg"))
    @Test fun invalidMonth() = assertNull(FileDates.fromName("IMG_20191301_080910.jpg"))
    @Test fun counterIsNotADate() = assertNull(FileDates.fromName("DSC_12345678.jpg"))
}
