package io.github.archonw.starswipe.ui.storages

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.github.archonw.starswipe.R
import io.github.archonw.starswipe.ui.UiText
import io.github.archonw.starswipe.ui.asString
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.archonw.starswipe.db.StorageLocationEntity
import io.github.archonw.starswipe.db.StorageType
import io.github.archonw.starswipe.ui.container
import io.github.archonw.starswipe.ui.factory
import io.github.archonw.starswipe.ui.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageListScreen(onAdd: () -> Unit, onFolders: (Long) -> Unit, onRate: (Long?) -> Unit, onSettings: () -> Unit) {
    val c = container()
    val vm: StorageListViewModel = viewModel(factory = factory { StorageListViewModel(c) })
    val locs by vm.locations.collectAsState()
    val unrated by vm.unrated.collectAsState()
    val scan by vm.scanning.collectAsState()
    val pending by vm.pending.collectAsState()
    val active = locs.filter { it.enabled }
    var confirm by remember { mutableStateOf<StorageLocationEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } }) },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, stringResource(R.string.add_storage)) } },
    ) { pad ->
        if (locs.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAdd) { Text(stringResource(R.string.add_storage)) }
            }
        } else
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (locs.isNotEmpty()) item {
                val total = active.sumOf { unrated[it.id] ?: 0 }
                Button(onClick = { onRate(null) }, enabled = active.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Default.Star, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (active.size > 1) R.string.rate_all else R.string.rate_start), style = MaterialTheme.typography.titleMedium)
                    if (total > 0) Text("  ($total)")
                }
                val waiting = pending.values.sum()
                if (waiting > 0) Card(Modifier.fillMaxWidth().padding(top = 10.dp), colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text(pluralStringResource(R.plurals.pending_upload, waiting, waiting), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        TextButton(onClick = { vm.uploadNow() }) { Text(stringResource(R.string.upload_now)) }
                    }
                }
                Text(stringResource(R.string.storages_header), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(locs, key = { it.id }) { l ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.titleMedium)
                                Text("${l.type.label()} · " + if (l.type == StorageType.LOCAL) stringResource(R.string.local_shared_folder)
                                    else if (l.folders.isEmpty()) stringResource(R.string.no_folders_chosen)
                                    else l.folders.joinToString(", ").ifEmpty { stringResource(R.string.root_folder) },
                                    style = MaterialTheme.typography.bodySmall)
                                pending[l.id]?.takeIf { it > 0 }?.let { n ->
                                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CloudUpload, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(pluralStringResource(R.plurals.pending_upload, n, n), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                scan?.takeIf { it.storageId == l.id }?.let { st ->
                                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(scanText(st.found, st.scanned).asString(), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            Switch(checked = l.enabled, onCheckedChange = { vm.setEnabled(l, it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.one_star_unrated), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = l.oneStarIsUnrated, onCheckedChange = { vm.setOneStar(l, it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (l.enabled) OutlinedButton(onClick = { onRate(l.id) }) {
                                Text(unrated[l.id]?.let { stringResource(R.string.rate_only_this_count, it) } ?: stringResource(R.string.rate_only_this))
                            }
                            if (l.type != StorageType.LOCAL) TextButton(onClick = { onFolders(l.id) }) { Text(stringResource(R.string.folders)) }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { confirm = l }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { l ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.remove_title, l.name)) },
            text = { Text(stringResource(R.string.remove_text)) },
            confirmButton = { TextButton(onClick = { vm.remove(l); confirm = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStorageScreen(onSaved: (Long, StorageType) -> Unit, onBack: () -> Unit) {
    val c = container()
    val ctx = LocalContext.current
    val vm: AddStorageViewModel = viewModel(factory = factory { AddStorageViewModel(c) })
    val f by vm.form.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val saved by vm.saved.collectAsState()
    val phoneName = stringResource(R.string.local_default_name)
    LaunchedEffect(saved) { saved?.let { onSaved(it.first, it.second) } }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.saveLocal(uri.toString(), DocumentFile.fromTreeUri(ctx, uri)?.name ?: phoneName)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.add_storage)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StorageType.entries.forEach { t ->
                    FilterChip(selected = f.type == t, onClick = { vm.update(f.copy(type = t)) }, label = { Text(t.label()) })
                }
            }
            field(stringResource(R.string.name_label), f.name) { vm.update(f.copy(name = it)) }
            when (f.type) {
                StorageType.NEXTCLOUD -> {
                    field(stringResource(R.string.nc_address), f.url, KeyboardType.Uri) { vm.update(f.copy(url = it)) }
                    Text(stringResource(R.string.nc_login_hint),
                        style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && f.url.isNotBlank(), onClick = {
                        vm.loginNextcloud { url -> CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url)) }
                    }) { Text(stringResource(R.string.nc_login_button)) }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)); Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.nc_waiting)); Spacer(Modifier.weight(1f)); TextButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) }
                    }
                }
                StorageType.WEBDAV -> {
                    field(stringResource(R.string.webdav_address), f.url, KeyboardType.Uri) { vm.update(f.copy(url = it)) }
                    field(stringResource(R.string.user), f.user) { vm.update(f.copy(user = it)) }
                    password(f.password) { vm.update(f.copy(password = it)) }
                    connectButton(busy, vm::connect)
                }
                StorageType.SMB -> {
                    field(stringResource(R.string.smb_server), f.host, KeyboardType.Uri) { vm.update(f.copy(host = it)) }
                    field(stringResource(R.string.smb_share), f.share) { vm.update(f.copy(share = it)) }
                    field(stringResource(R.string.smb_path), f.basePath) { vm.update(f.copy(basePath = it)) }
                    field(stringResource(R.string.user), f.user) { vm.update(f.copy(user = it)) }
                    password(f.password) { vm.update(f.copy(password = it)) }
                    field(stringResource(R.string.smb_domain), f.domain) { vm.update(f.copy(domain = it)) }
                    Text(stringResource(R.string.smb_vpn_hint), style = MaterialTheme.typography.bodySmall)
                    connectButton(busy, vm::connect)
                }
                StorageType.LOCAL -> {
                    Text(stringResource(R.string.local_hint),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { treePicker.launch(null) }) { Text(stringResource(R.string.choose_folder)) }
                }
            }
            error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun field(label: String, value: String, kb: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb))

@Composable
private fun password(value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))

@Composable
private fun connectButton(busy: Boolean, onClick: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Button(enabled = !busy, onClick = onClick) { Text(stringResource(R.string.connect)) }
    if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(id: Long, onDone: () -> Unit) {
    val c = container()
    val vm: FolderPickerViewModel = viewModel(key = "folders-$id", factory = factory { FolderPickerViewModel(c, id) })
    val dir by vm.dir.collectAsState()
    val folders by vm.folders.collectAsState()
    val selected by vm.selected.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val done by vm.done.collectAsState()
    LaunchedEffect(done) { if (done) onDone() }

    Scaffold(topBar = { TopAppBar(title = { Text(if (dir.isEmpty()) stringResource(R.string.folders_title) else dir) }, navigationIcon = {
        IconButton(onClick = { if (dir.isEmpty()) onDone() else vm.up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.selected_count, selected.size), Modifier.weight(1f))
                OutlinedButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = vm::save) { Text(stringResource(R.string.done)) }
            }
        }) { pad ->
        Column(Modifier.padding(pad)) {
            if (busy) CircularProgressIndicator(Modifier.padding(16.dp))
            error?.let { Text(it.asString(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (dir.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = dir in selected, onCheckedChange = { vm.toggle(dir) })
                Text(stringResource(R.string.this_folder_whole, dir.substringAfterLast('/')), style = MaterialTheme.typography.bodyLarge)
            }
            LazyColumn {
                items(folders, key = { it.path }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { vm.open(e.path) }.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = e.path in selected, onCheckedChange = { vm.toggle(e.path) })
                        Icon(Icons.Default.Folder, null)
                        Spacer(Modifier.width(8.dp))
                        Text(e.name, Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, stringResource(R.string.open))
                    }
                }
            }
            if (!busy && folders.isEmpty() && error == null) Text(stringResource(R.string.no_subfolders), Modifier.padding(16.dp))
        }
    }
}

/** Text neben dem Wartekreis eines Speicherorts, der gerade eingelesen wird. */
internal fun scanText(found: Int, scanned: Int): UiText = when {
    found == 0 -> UiText.Res(R.string.scan_starting)
    else -> UiText.Plural(R.plurals.scan_progress, found, listOf(scanned, found))
}
