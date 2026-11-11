package io.github.archonw.starswipe.ui.rate

import io.github.archonw.starswipe.settings.Direction
import io.github.archonw.starswipe.settings.UiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeRatingTest {
    private val w = 1000f
    private val h = 2000f
    private val map = UiSettings.DEFAULT_SWIPE

    @Test fun upAboveThreshold() = assertEquals(5, SwipeRating.resolve(0f, -0.3f * h, w, h, 0f, 0f, map))
    @Test fun belowThresholdIsNull() = assertNull(SwipeRating.resolve(0.2f * w, 0f, w, h, 0f, 0f, map))
    @Test fun flingTriggers() = assertEquals(4, SwipeRating.resolve(0.05f * w, 0f, w, h, 2000f, 0f, map))
    @Test fun flingAgainstDragIsNull() = assertNull(SwipeRating.resolve(0.05f * w, 0f, w, h, -2000f, 0f, map))
    @Test fun dominantAxisWins() = assertEquals(2, SwipeRating.resolve(0.3f * w, 0.4f * h, w, h, 0f, 0f, map))
    @Test fun leftIsThree() = assertEquals(3, SwipeRating.resolve(-0.3f * w, 0f, w, h, 0f, 0f, map))
    @Test fun customMapping() = assertEquals(1, SwipeRating.resolve(0f, -0.3f * h, w, h, 0f, 0f, map + (Direction.UP to 1)))
    @Test fun directionWhileDragging() {
        assertEquals(Direction.RIGHT, SwipeRating.direction(30f, 10f))
        assertEquals(Direction.UP, SwipeRating.direction(10f, -30f))
        assertNull(SwipeRating.direction(0f, 0f))
    }
    @Test fun progressIsRelativeToThreshold() = assertEquals(0.5f, SwipeRating.progress(0.125f * w, 0f, w, h), 0.001f)
}
