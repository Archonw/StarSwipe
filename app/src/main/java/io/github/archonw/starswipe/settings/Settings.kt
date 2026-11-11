package io.github.archonw.starswipe.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class Direction { UP, RIGHT, LEFT, DOWN }
enum class RatingMode { STAR_BAR, SWIPE }

data class UiSettings(
    val mode: RatingMode = RatingMode.STAR_BAR,
    val swipeMap: Map<Direction, Int> = DEFAULT_SWIPE,
    /** Sternzahl des Bewertungsknopfs im Wisch-Modus. */
    val buttonStars: Int = 1,
) {
    companion object {
        val DEFAULT_SWIPE = mapOf(Direction.UP to 5, Direction.RIGHT to 4, Direction.LEFT to 3, Direction.DOWN to 2)
    }
}

/** Bedien-Einstellungen (DataStore). Pro Prozess nur eine Instanz je [name]. */
class Settings(context: Context, name: String = "settings") {
    private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }
    private val modeKey = stringPreferencesKey("mode")
    private val buttonKey = intPreferencesKey("button_stars")
    private fun swipeKey(d: Direction) = intPreferencesKey("swipe_${d.name}")

    val ui: Flow<UiSettings> = store.data.map { p ->
        UiSettings(
            mode = p[modeKey]?.let { runCatching { RatingMode.valueOf(it) }.getOrNull() } ?: RatingMode.STAR_BAR,
            swipeMap = Direction.entries.associateWith { p[swipeKey(it)] ?: UiSettings.DEFAULT_SWIPE.getValue(it) },
            buttonStars = p[buttonKey] ?: 1,
        )
    }

    suspend fun setMode(m: RatingMode) { store.edit { it[modeKey] = m.name } }
    suspend fun setButtonStars(stars: Int) { store.edit { it[buttonKey] = stars.coerceIn(1, 5) } }
    suspend fun setSwipe(d: Direction, stars: Int) { store.edit { it[swipeKey(d)] = stars.coerceIn(1, 5) } }
}
