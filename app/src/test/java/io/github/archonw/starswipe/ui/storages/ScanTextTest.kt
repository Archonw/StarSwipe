package io.github.archonw.starswipe.ui.storages

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ScanTextTest {
    private fun t(found: Int, scanned: Int) = scanText(found, scanned).resolve(ApplicationProvider.getApplicationContext<Context>().resources)

    @Test @Config(qualifiers = "de") fun german() {
        assertEquals("Wird eingelesen …", t(0, 0))
        assertEquals("Wird eingelesen · 5 von 12 Fotos", t(12, 5))
    }
    @Test @Config(qualifiers = "en") fun english() {
        assertEquals("Scanning · 0 of 1 photo", t(1, 0))
        assertEquals("Scanning · 5 of 12 photos", t(12, 5))
    }
    @Test @Config(qualifiers = "es") fun spanish() = assertEquals("Escaneando · 5 de 12 fotos", t(12, 5))
}
