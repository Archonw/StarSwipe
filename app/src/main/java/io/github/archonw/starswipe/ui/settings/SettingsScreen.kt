package io.github.archonw.starswipe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.archonw.starswipe.settings.Direction
import io.github.archonw.starswipe.settings.RatingMode
import io.github.archonw.starswipe.settings.UiSettings
import io.github.archonw.starswipe.ui.container
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.archonw.starswipe.R
import io.github.archonw.starswipe.ui.UiText
import io.github.archonw.starswipe.ui.asString
import io.github.archonw.starswipe.ui.uiText

@Composable
private fun Direction.label() = stringResource(when (this) {
    Direction.UP -> R.string.dir_up
    Direction.RIGHT -> R.string.dir_right
    Direction.LEFT -> R.string.dir_left
    Direction.DOWN -> R.string.dir_down
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onStorages: () -> Unit, onBack: () -> Unit, onAbout: () -> Unit = {}) {
    val c = container()
    val ui by c.settings.ui.collectAsState(initial = UiSettings())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_controls), style = MaterialTheme.typography.titleMedium)
            RatingMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().selectable(selected = ui.mode == m, role = Role.RadioButton,
                    onClick = { scope.launch { c.settings.setMode(m) } }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ui.mode == m, onClick = null)
                    Column {
                        Text(stringResource(if (m == RatingMode.STAR_BAR) R.string.mode_star_bar else R.string.mode_swipe))
                        Text(stringResource(if (m == RatingMode.STAR_BAR) R.string.mode_star_bar_hint else R.string.mode_swipe_hint),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.swipe_mapping), style = MaterialTheme.typography.titleMedium)
            Direction.entries.forEach { d ->
                StarsChooser(d.label(), ui.swipeMap.getValue(d)) { n -> scope.launch { c.settings.setSwipe(d, n) } }
            }
            StarsChooser(stringResource(R.string.swipe_button), ui.buttonStars) { n -> scope.launch { c.settings.setButtonStars(n) } }
            HorizontalDivider()
            Text(stringResource(R.string.storages_header), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().toggleable(value = ui.autoScan, role = Role.Switch,
                onValueChange = { on -> scope.launch { c.settings.setAutoScan(on) } }), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_scan))
                    Text(stringResource(R.string.auto_scan_hint), style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.material3.Switch(checked = ui.autoScan, onCheckedChange = null)
            }
            Text(stringResource(R.string.settings_storages_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onStorages) { Text(stringResource(R.string.to_home)) }
            HorizontalDivider()
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.language_hint), style = MaterialTheme.typography.bodySmall)
            // Ab Android 13: Systemseite „App-Sprache“ für genau diese App
            if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }
            }) { Text(stringResource(R.string.language_button)) }
            HorizontalDivider()
            Text(aboutText().asString(),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAbout) { Text(stringResource(R.string.about_button)) }
        }
    }
}

/** Zeile „Beschriftung … [n ★]“ mit Auswahl 1–5 Sterne. */
@Composable
private fun StarsChooser(label: String, stars: Int, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text("$stars ★") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (1..5).forEach { n -> DropdownMenuItem(text = { Text("$n ★") }, onClick = { open = false; onChoose(n) }) }
            }
        }
    }
}

/** Fußzeile der Einstellungen; Versionsnummer aus dem Build. */
fun aboutText(): UiText = uiText(R.string.settings_footer, io.github.archonw.starswipe.BuildConfig.VERSION_NAME)
