package io.github.archonw.starswipe.ui.rate

import io.github.archonw.starswipe.settings.Direction
import kotlin.math.abs

/** Wischgeste → Sternzahl. Schwelle 25 % der Breite/Höhe oder Fling ≥ 1500 px/s. */
object SwipeRating {
    const val THRESHOLD = 0.25f
    const val FLING = 1500f

    fun direction(dx: Float, dy: Float): Direction? = when {
        dx == 0f && dy == 0f -> null
        abs(dx) >= abs(dy) -> if (dx > 0) Direction.RIGHT else Direction.LEFT
        else -> if (dy > 0) Direction.DOWN else Direction.UP
    }

    /** 0..1: wie weit die Geste bis zur Auslöse-Schwelle ist. */
    fun progress(dx: Float, dy: Float, w: Float, h: Float): Float {
        val d = direction(dx, dy) ?: return 0f
        val p = if (d == Direction.LEFT || d == Direction.RIGHT) abs(dx) / (THRESHOLD * w) else abs(dy) / (THRESHOLD * h)
        return p.coerceIn(0f, 1f)
    }

    fun resolve(dx: Float, dy: Float, w: Float, h: Float, vx: Float, vy: Float, map: Map<Direction, Int>): Int? {
        val d = direction(dx, dy) ?: return null
        val horizontal = d == Direction.LEFT || d == Direction.RIGHT
        val dist = if (horizontal) dx else dy
        val v = if (horizontal) vx else vy
        val far = abs(dist) >= THRESHOLD * (if (horizontal) w else h)
        val fling = abs(v) >= FLING && (v > 0) == (dist > 0)
        return if (far || fling) map[d] else null
    }
}
