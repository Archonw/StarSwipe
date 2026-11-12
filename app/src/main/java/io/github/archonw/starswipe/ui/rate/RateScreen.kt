package io.github.archonw.starswipe.ui.rate

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import io.github.archonw.starswipe.db.PhotoEntity
import io.github.archonw.starswipe.settings.Direction
import io.github.archonw.starswipe.settings.RatingMode
import io.github.archonw.starswipe.ui.Gold
import io.github.archonw.starswipe.ui.container
import io.github.archonw.starswipe.ui.factory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.archonw.starswipe.R
import io.github.archonw.starswipe.ui.asString
import kotlin.math.abs

/** Datum im Format der Handysprache. */
private fun dateFormat() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(java.util.Locale.getDefault())

@Composable
fun RateScreen(only: Long?, onSettings: () -> Unit, onStorages: () -> Unit, onHome: () -> Unit) {
    val c = container()
    val vm: RateViewModel = viewModel(key = "rate-$only", factory = factory { RateViewModel(c, only) })
    val s by vm.state.collectAsState()
    val ui by vm.ui.collectAsState()
    val hasLocations by vm.hasLocations.collectAsState()
    val ctx = LocalContext.current
    var jumpList by remember { mutableStateOf<List<FolderJump>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Vorladen der nächsten Fotos
    LaunchedEffect(s.upcoming) {
        s.upcoming.forEach { p -> vm.preview(p)?.let { c.imageLoader.enqueue(ImageRequest.Builder(ctx).data(it).build()) } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val cur = s.current
        when {
            hasLocations == false -> Center {
                Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.welcome_hint), color = Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onStorages) { Text(stringResource(R.string.add_storage)) }
            }
            cur == null && s.loading -> Center {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.loading_photos), color = Color.White)
                Text(when {
                    s.found == 0 -> stringResource(R.string.searching_folders)
                    else -> pluralStringResource(R.plurals.found_checked, s.found, s.found, s.scanned)
                }, color = Color(0xFFCCCCCC))
                if (s.found > 0 && s.scanned == 0) Text(stringResource(R.string.first_appear), color = Color(0xFF999999),
                    style = MaterialTheme.typography.bodySmall)
            }
            cur == null -> Center {
                Text(stringResource(R.string.all_rated), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.refresh() }) { Text(stringResource(R.string.rescan)) }
                if (s.canUndo || s.canGoBack) Row {
                    if (s.canGoBack) TextButton(onClick = { vm.back() }) { Text(stringResource(R.string.back)) }
                    if (s.canUndo) TextButton(onClick = { vm.undo() }) { Text(stringResource(R.string.undo)) }
                }
            }
            else -> PhotoPager(cur, ui.mode, ui.swipeMap, vm,
                onRate = { vm.rate(it) }, onNext = { vm.skip() }, onPrev = { vm.back() })
        }

        // Kopfzeile
        Row(Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (s.showRated) pluralStringResource(R.plurals.photos_count, s.remaining, s.remaining)
                    else pluralStringResource(R.plurals.unrated_left, s.remaining, s.remaining),
                    color = Color.White, fontWeight = FontWeight.Bold)
                cur?.let {
                    Text("${s.storageName} · ${it.folder.ifEmpty { "/" }} · " +
                        dateFormat().format(Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault())),
                        color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (s.loading && cur != null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (s.found > 0) Text(" ${s.scanned}/${s.found}", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall)
            }
            if (s.remaining > 0 || s.canGoBack) IconButton(onClick = { scope.launch { jumpList = vm.folders() } }) {
                Icon(Icons.Default.FolderOpen, stringResource(R.string.jump_to_folder), tint = Color.White)
            }
            IconButton(onClick = { vm.setShowRated(!s.showRated) }) {
                Icon(if (s.showRated) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    stringResource(if (s.showRated) R.string.show_only_unrated else R.string.show_rated_too), tint = Color.White)
            }
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.rescan), tint = Color.White) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = Color.White) }
        }

        jumpList?.let { list ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { jumpList = null },
                confirmButton = { TextButton(onClick = { jumpList = null }) { Text(stringResource(R.string.cancel)) } },
                title = { Text(stringResource(R.string.jump_to_folder)) },
                text = {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                        initialFirstVisibleItemIndex = list.indexOfFirst { it.current }.coerceAtLeast(0))
                    androidx.compose.foundation.lazy.LazyColumn(state = listState) {
                        items(list.size) { i ->
                            val f = list[i]
                            Row(Modifier.fillMaxWidth()
                                .background(if (f.current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable { vm.jumpTo(f); jumpList = null }
                                .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(f.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (f.current) FontWeight.Bold else FontWeight.Normal)
                                Text(pluralStringResource(R.plurals.photos_count, f.count, f.count),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
            )
        }

        // Fußzeile
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Pfad mit Dateiname im Speicherort; lange Pfade dürfen zweizeilig werden
            cur?.let {
                Text(it.path, color = Color(0xFFDDDDDD), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
            }
            s.message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.asString(), color = Color(0xFFFFB4A9), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearMessage() }) { Text(stringResource(R.string.ok)) }
                }
            }
            if (s.pending > 0) Text(pluralStringResource(R.plurals.pending_upload, s.pending, s.pending),
                color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall)
            if (cur != null && ui.mode == RatingMode.STAR_BAR) StarBar(cur.rating) { vm.rate(it) }
            if (cur != null && ui.mode == RatingMode.SWIPE) {
                Text(listOf(Direction.UP to "↑", Direction.RIGHT to "→", Direction.LEFT to "←", Direction.DOWN to "↓")
                    .joinToString("   ") { (d, a) -> "$a ${ui.swipeMap[d]}★" }, color = Color(0xFFCCCCCC),
                    style = MaterialTheme.typography.bodySmall)
            }
            // Wisch-Modus: links/rechts vergeben Sterne, daher eigene Knöpfe fürs Blättern.
            if (cur != null && ui.mode == RatingMode.SWIPE) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = s.canGoBack, onClick = { vm.back() }) { Text(stringResource(R.string.previous)) }
                OutlinedButton(onClick = { vm.skip() }) { Icon(Icons.Default.SkipNext, null); Text(" " + stringResource(R.string.skip)) }
                OutlinedButton(onClick = { vm.rate(ui.buttonStars) }) { Text("${ui.buttonStars} ★") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onHome) { Icon(Icons.Default.Home, null); Text(" " + stringResource(R.string.home)) }
                TextButton(enabled = s.canUndo, onClick = { vm.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, null); Text(" " + stringResource(R.string.undo)) }
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) =
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }

@Composable
private fun StarBar(current: Int?, onRate: (Int) -> Unit) = Row(horizontalArrangement = Arrangement.Center) {
    (1..5).forEach { n ->
        IconButton(onClick = { onRate(n) }, modifier = Modifier.size(64.dp)) {
            Icon(if (current != null && n <= current) Icons.Default.Star else Icons.Default.StarBorder,
                pluralStringResource(R.plurals.stars, n, n), tint = Gold, modifier = Modifier.size(52.dp))
        }
    }
}

/** Ein Foto mit Zoom (zwei Finger) und Wischgesten (ein Finger, nur ungezoomt). */
@Composable
private fun PhotoPager(
    photo: PhotoEntity, mode: RatingMode, swipeMap: Map<Direction, Int>, vm: RateViewModel,
    onRate: (Int) -> Unit, onNext: () -> Unit, onPrev: () -> Unit,
) {
    val c = container()
    val key = photo.storageId to photo.path
    val src by produceState<Any?>(null, key) { value = vm.preview(photo) }
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    var drag by remember(key) { mutableStateOf(Offset.Zero) }
    var imgState by remember(key) { mutableStateOf<AsyncImagePainter.State?>(null) }
    val failed = imgState is AsyncImagePainter.State.Error
    val loading = src == null || imgState == null || imgState is AsyncImagePainter.State.Loading || imgState is AsyncImagePainter.State.Empty

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        val h = with(LocalDensity.current) { maxHeight.toPx() }
        val shownDrag = if (mode == RatingMode.STAR_BAR) Offset(drag.x, 0f) else drag

        Box(Modifier.fillMaxSize()
            .pointerInput(key, mode, swipeMap) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var zoomed = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            zoomed = true
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            drag = Offset.Zero
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && !zoomed) {
                            val ch = pressed[0]
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if (scale > 1f) offset += ch.positionChange() else drag += ch.positionChange()
                            ch.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (!zoomed && scale == 1f) {
                        val v = tracker.calculateVelocity()
                        when (mode) {
                            RatingMode.SWIPE -> SwipeRating.resolve(drag.x, drag.y, w, h, v.x, v.y, swipeMap)?.let(onRate)
                            RatingMode.STAR_BAR -> {
                                val horizontal = abs(drag.x) > abs(drag.y)
                                val far = abs(drag.x) >= SwipeRating.THRESHOLD * w
                                val fling = abs(v.x) >= SwipeRating.FLING && (v.x > 0) == (drag.x > 0)
                                if (horizontal && (far || fling)) { if (drag.x < 0) onNext() else onPrev() }
                            }
                        }
                    }
                    drag = Offset.Zero
                }
            }
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x + shownDrag.x; translationY = offset.y + shownDrag.y
                rotationZ = if (mode == RatingMode.SWIPE) shownDrag.x / w * 8f else 0f
            }) {
            if (src != null) AsyncImage(
                model = src, contentDescription = photo.name, imageLoader = c.imageLoader,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                onState = { imgState = it },
            )
        }
        if (loading && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (failed) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.no_preview), color = Color.White)
            Text(photo.name, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall)
            (imgState as? AsyncImagePainter.State.Error)?.result?.throwable?.let { t ->
                Text(if (t is PreviewTimeoutException) stringResource(R.string.err_preview_timeout, t.seconds.toInt())
                    else t.message ?: t.javaClass.simpleName, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
            }
        }
        // Sternzahl während des Wischens
        if (mode == RatingMode.SWIPE && drag != Offset.Zero) {
            val d = SwipeRating.direction(drag.x, drag.y)
            val p = SwipeRating.progress(drag.x, drag.y, w, h)
            val stars = d?.let { swipeMap[it] } ?: 0
            Text("★".repeat(stars), color = Gold.copy(alpha = 0.3f + 0.7f * p), fontSize = 56.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center)
                    .graphicsLayer { scaleX = 0.8f + 0.4f * p; scaleY = 0.8f + 0.4f * p })
        }
        if (photo.rating != null) Text("★".repeat(photo.rating), color = Gold, fontSize = 22.sp,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 16.dp))
    }
}
