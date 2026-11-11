package io.github.archonw.starswipe.ui.about

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.archonw.starswipe.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AboutInfoTest {
    private fun text(): String {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        return AboutInfo.sections.joinToString("\n") { it.title.resolve(res) + "\n" + it.body.resolve(res) }
    }

    @Test @Config(qualifiers = "de") fun german() {
        val t = text()
        assertTrue(t, t.contains("KI") && t.contains("Claude") && t.contains("ohne jede Gewährleistung"))
    }
    @Test @Config(qualifiers = "en") fun english() {
        val t = text()
        assertTrue(t, t.contains("Built with AI") && t.contains("Claude") && t.contains("without any warranty"))
    }
    @Test @Config(qualifiers = "es") fun spanish() {
        val t = text()
        assertTrue(t, t.contains("Hecha con IA") && t.contains("Claude") && t.contains("sin ninguna garantía"))
    }
    @Test fun namesDeveloperLicenseLibrariesVersion() {
        val t = text()
        assertTrue(t, t.contains("Jan Borghoff") && t.contains("GPL-3.0") && t.contains(BuildConfig.VERSION_NAME))
        assertTrue(t, listOf("smbj", "OkHttp", "Coil", "AndroidX").all { t.contains(it) })
    }
    @Test fun licenseUrl() = assertEquals("https://www.gnu.org/licenses/gpl-3.0.html", AboutInfo.LICENSE_URL)
}
