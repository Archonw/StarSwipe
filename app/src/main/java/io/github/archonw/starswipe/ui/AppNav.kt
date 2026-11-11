package io.github.archonw.starswipe.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.archonw.starswipe.db.StorageType
import io.github.archonw.starswipe.ui.about.AboutScreen
import io.github.archonw.starswipe.ui.rate.RateScreen
import io.github.archonw.starswipe.ui.settings.SettingsScreen
import io.github.archonw.starswipe.ui.storages.AddStorageScreen
import io.github.archonw.starswipe.ui.storages.FolderPickerScreen
import io.github.archonw.starswipe.ui.storages.StorageListScreen

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        // Startseite: Speicherorte mit „Bewerten starten“; hier wird noch nichts geladen.
        composable("home") {
            StorageListScreen(onAdd = { nav.navigate("storages/add") }, onFolders = { nav.navigate("storages/$it/folders") },
                onRate = { only -> nav.navigate("rate/${only ?: -1L}") }, onSettings = { nav.navigate("settings") })
        }
        composable("rate/{only}", arguments = listOf(navArgument("only") { type = NavType.LongType })) {
            val only = it.arguments!!.getLong("only").takeIf { id -> id >= 0 }
            val home = { nav.popBackStack("home", inclusive = false); Unit }
            RateScreen(only, onSettings = { nav.navigate("settings") }, onStorages = home, onHome = home)
        }
        composable("settings") {
            SettingsScreen(onStorages = { nav.popBackStack("home", inclusive = false) }, onBack = { nav.popBackStack() },
                onAbout = { nav.navigate("about") })
        }
        composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
        composable("storages/add") {
            AddStorageScreen(onSaved = { id, type ->
                nav.popBackStack()
                if (type != StorageType.LOCAL) nav.navigate("storages/$id/folders")
            }, onBack = { nav.popBackStack() })
        }
        composable("storages/{id}/folders", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            FolderPickerScreen(it.arguments!!.getLong("id"), onDone = { nav.popBackStack() })
        }
    }
}
