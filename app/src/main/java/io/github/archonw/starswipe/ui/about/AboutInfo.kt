package io.github.archonw.starswipe.ui.about

import io.github.archonw.starswipe.BuildConfig
import io.github.archonw.starswipe.R
import io.github.archonw.starswipe.ui.UiText
import io.github.archonw.starswipe.ui.uiText

/** Inhalte der Seite „Über StarSwipe“ (Impressum, Lizenz, KI-Hinweis); Texte in der Sprache des Handys. */
object AboutInfo {
    const val DEVELOPER = "Jan Borghoff"
    /** Kontakt-Adresse; null = keine anzeigen. */
    val CONTACT_EMAIL: String? = null
    const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    data class Section(val title: UiText, val body: UiText)

    val sections: List<Section> = listOf(
        Section(uiText(R.string.about_app_title, BuildConfig.VERSION_NAME), uiText(R.string.about_app_body)),
        Section(uiText(R.string.about_dev_title), UiText.Raw(DEVELOPER + (CONTACT_EMAIL?.let { "\n$it" } ?: ""))),
        Section(uiText(R.string.about_ai_title), uiText(R.string.about_ai_body, DEVELOPER)),
        Section(uiText(R.string.about_license_title), uiText(R.string.about_license_body)),
        Section(uiText(R.string.about_libs_title), uiText(R.string.about_libs_body)),
    )
}
