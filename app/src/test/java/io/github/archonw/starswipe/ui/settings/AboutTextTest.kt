package io.github.archonw.starswipe.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.archonw.starswipe.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AboutTextTest {
    /** Versionsnummer kommt aus dem Build, nicht aus festem Text. */
    @Test fun showsBuildVersion() {
        val t = aboutText().resolve(ApplicationProvider.getApplicationContext<Context>().resources)
        assertTrue(t, t.startsWith("StarSwipe ${BuildConfig.VERSION_NAME} ·"))
    }
}
