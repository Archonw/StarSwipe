package io.github.archonw.starswipe.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Text, der erst beim Anzeigen in die Sprache des Handys übersetzt wird.
 * So bleiben Logik-Klassen ohne Android-Ressourcen testbar.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    /** Unübersetzbares, z. B. ein Speicherort-Name oder eine technische Fehlermeldung. */
    data class Raw(val text: String) : UiText
    data class Lines(val lines: List<UiText>) : UiText

    fun resolve(res: Resources): String = when (this) {
        is Res -> res.getString(id, *args.map { it.arg(res) }.toTypedArray())
        is Plural -> res.getQuantityString(id, count, *args.map { it.arg(res) }.toTypedArray())
        is Raw -> text
        is Lines -> lines.joinToString("\n") { it.resolve(res) }
    }

    private fun Any.arg(res: Resources): Any = if (this is UiText) resolve(res) else this
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

@Composable
fun UiText.asString(): String {
    LocalConfiguration.current // bei Sprachwechsel neu zeichnen
    return resolve(LocalContext.current.resources)
}
