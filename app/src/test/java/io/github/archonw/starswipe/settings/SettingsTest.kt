package io.github.archonw.starswipe.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private val s = Settings(ApplicationProvider.getApplicationContext(), "test-${UUID.randomUUID()}")

    @Test fun defaultsMatchSpec() = runBlocking {
        val ui = s.ui.first()
        assertEquals(RatingMode.STAR_BAR, ui.mode)
        assertEquals(mapOf(Direction.UP to 5, Direction.RIGHT to 4, Direction.LEFT to 3, Direction.DOWN to 2), ui.swipeMap)
    }

    @Test fun setSwipePersists() = runBlocking {
        s.setSwipe(Direction.UP, 1)
        s.setMode(RatingMode.SWIPE)
        val ui = s.ui.first()
        assertEquals(1, ui.swipeMap[Direction.UP])
        assertEquals(RatingMode.SWIPE, ui.mode)
    }

    @Test fun swipeValueClampedTo1to5() = runBlocking {
        s.setSwipe(Direction.DOWN, 9); assertEquals(5, s.ui.first().swipeMap[Direction.DOWN])
        s.setSwipe(Direction.DOWN, 0); assertEquals(1, s.ui.first().swipeMap[Direction.DOWN])
    }

    @Test fun buttonStarsDefaultsTo1AndPersists() = runBlocking {
        assertEquals(1, s.ui.first().buttonStars)
        s.setButtonStars(3); assertEquals(3, s.ui.first().buttonStars)
        s.setButtonStars(7); assertEquals(5, s.ui.first().buttonStars)
    }
}
