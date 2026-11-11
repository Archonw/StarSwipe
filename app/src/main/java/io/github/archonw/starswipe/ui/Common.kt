package io.github.archonw.starswipe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.archonw.starswipe.AppContainer
import io.github.archonw.starswipe.StarSwipeApp
import io.github.archonw.starswipe.db.StorageType

val Gold = Color(0xFFFFC83D)

@Composable
fun StarSwipeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, secondary = Gold, background = Color(0xFF101114), surface = Color(0xFF17181C)), content = content)
}

@Composable
fun container(): AppContainer = (LocalContext.current.applicationContext as StarSwipeApp).container

/** Kleine Fabrik für ViewModels mit Konstruktor-Argumenten. */
inline fun <reified T : ViewModel> factory(crossinline create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>, extras: CreationExtras): V = create() as V
}

@androidx.compose.runtime.Composable
fun StorageType.label() = when (this) {
    StorageType.NEXTCLOUD -> "Nextcloud"
    StorageType.WEBDAV -> "WebDAV"
    StorageType.SMB -> "SMB"
    StorageType.LOCAL -> androidx.compose.ui.res.stringResource(io.github.archonw.starswipe.R.string.storage_local)
}
