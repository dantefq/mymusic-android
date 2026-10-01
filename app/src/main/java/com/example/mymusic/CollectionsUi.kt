package com.example.mymusic

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

fun myWaveMix(tracks: List<Track>, plays: List<PlayEvent>, seed: Int = 0): List<Track> {
    val counts = plays.groupingBy { it.trackId }.eachCount()
    val favoriteGenres = tracks.filter { (counts[it.id] ?: 0) > 0 }
        .groupingBy { it.genre }.eachCount()
    val favoriteArtists = tracks.filter { (counts[it.id] ?: 0) > 0 }
        .groupingBy { it.artist }.eachCount()
    val now = System.currentTimeMillis()
    val random = kotlin.random.Random(seed)
    return tracks.map { track ->
        val weight = 1.0 + (counts[track.id] ?: 0).coerceAtMost(10) * 2 +
            (favoriteGenres[track.genre] ?: 0) + (favoriteArtists[track.artist] ?: 0) * 2 +
            (if (track.addedAt > now - 14L * 86400000) 3 else 0)
        track to -kotlin.math.ln(random.nextDouble().coerceAtLeast(1e-9)) / weight
    }.sortedBy { it.second }.map { it.first }
}

@Composable fun CollectionHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp)) {
        Text(title, color = white, fontSize = 32.sp, fontFamily = headingFont)
        Text(subtitle, color = muted, fontSize = 13.sp)
    }
}

@Composable fun CollectionTrack(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track, Modifier.size(48.dp), 5.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = white, fontFamily = headingFont, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            ArtistCredits(track, size = 12)
        }
        IconButton(onClick = onClick) { Icon(Icons.Default.PlayArrow, l("Play"), tint = accent) }
    }
}

@Composable fun MyWaveScreen(tracks: List<Track>, plays: List<PlayEvent>, onPlay: (List<Track>, Track) -> Unit,
    modifier: Modifier = Modifier) {
    val mix = remember(tracks, plays) { myWaveMix(tracks, plays).take(20) }
    LazyColumn(modifier.fillMaxSize()) {
        item { CollectionHeader(l("My Wave"), l("An endless mix shaped by your listening")) }
        items(mix, key = { it.id }) { track -> CollectionTrack(track, { onPlay(mix, track) }) }
    }
}

@Composable fun AudiobooksScreen(tracks: List<Track>, onPlay: (List<Track>, Track) -> Unit,
    modifier: Modifier = Modifier) {
    val books = remember(tracks) { tracks.filter { it.isAudiobook } }
    LazyColumn(modifier.fillMaxSize()) {
        item { CollectionHeader(l("Audiobooks"), stringResource(R.string.songs_count, books.size)) }
        items(books, key = { it.id }, contentType = { "book" }) { track -> CollectionTrack(track, { onPlay(books, track) }) }
        if (books.isEmpty()) item { Text(l("Audiobooks in your Audiobooks folder appear here."), color = muted, modifier = Modifier.padding(horizontal = 24.dp)) }
    }
}

@Composable fun GenresScreen(tracks: List<Track>, onPlay: (List<Track>, Track) -> Unit,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier) {
    var chosen by remember { mutableStateOf<String?>(null) }
    val grouped = tracks.groupBy { it.genre.ifBlank { "Other" } }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            CollectionHeader(chosen?.takeUnless { it == "Other" } ?: l(chosen ?: "Genres"),
                if (chosen == null) l("Explore your library by sound")
                else stringResource(R.string.songs_count, grouped[chosen]?.size ?: 0))
            if (chosen == null) TextButton(onClick = onFetch, modifier = Modifier.padding(start = 16.dp)) {
                Text(l("Fetch genres"))
            }
            if (chosen != null) TextButton(onClick = { chosen = null }, modifier = Modifier.padding(start = 16.dp)) {
                Text(l("All genres"))
            }
        }
        if (chosen == null) {
            items(grouped.entries.sortedBy { it.key }) { entry ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(16.dp)).background(panel)
                    .clickable { chosen = entry.key }.padding(20.dp)) {
                    Text(if (entry.key == "Other") l("Other") else entry.key.replaceFirstChar { it.uppercase() }, color = white,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${entry.value.size}", color = accent)
                }
            }
        } else {
            val list = grouped[chosen].orEmpty()
            items(list, key = { it.id }) { track -> CollectionTrack(track, { onPlay(list, track) }) }
        }
    }
}

@Composable fun StatsScreen(tracks: List<Track>, plays: List<PlayEvent>, onBack: () -> Unit,
    modifier: Modifier = Modifier) {
    var month by remember { mutableStateOf(false) }
    val since = System.currentTimeMillis() - (if (month) 30L else 7L) * 86400000
    val recent = remember(plays, month) { plays.filter { it.playedAt >= since } }
    val count = remember(recent) { recent.groupingBy { it.trackId }.eachCount() }
    val byId = remember(tracks) { tracks.associateBy { it.id } }
    val top = remember(tracks, count) {
        tracks.filter { it.id in count }.sortedByDescending { count[it.id] }.take(10)
    }
    val artists = remember(recent, byId) {
        recent.mapNotNull { byId[it.trackId]?.artist }.groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.second }.take(5)
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 16.dp)) { Text(l("Back")) }
            CollectionHeader(l("Listening"), stringResource(R.string.plays_period, recent.size, if (month) 30 else 7))
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !month, onClick = { month = false }, label = { Text(l("Week")) })
                FilterChip(selected = month, onClick = { month = true }, label = { Text(l("Month")) })
            }
            Spacer(Modifier.height(24.dp))
            Text(l("TOP TRACKS"), color = accent, fontSize = 11.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
        }
        items(top, key = { it.id }) { track ->
            StatBar(track.title, count[track.id] ?: 0, top.maxOfOrNull { count[it.id] ?: 0 } ?: 1)
        }
        item {
            Spacer(Modifier.height(30.dp))
            Text(l("TOP ARTISTS"), color = accent, fontSize = 11.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
        }
        items(artists) { (name, total) -> StatBar(name, total, artists.firstOrNull()?.second ?: 1) }
    }
}

@Composable private fun StatBar(label: String, value: Int, max: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 9.dp)) {
        Row {
            Text(label, color = white, modifier = Modifier.weight(1f), maxLines = 1)
            Text("$value", color = muted)
        }
        Spacer(Modifier.height(7.dp))
        LinearProgressIndicator(progress = { (value.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = accent, trackColor = raised)
    }
}

@Composable fun PlaylistsScreen(db: MusicDb, tracks: List<Track>, onPlay: (List<Track>, Track) -> Unit,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by remember(db) { db.playlists().observe() }.collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableStateOf<Playlist?>(null) }
    var create by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val entries by remember(db, selected?.id) {
        selected?.let { db.playlists().entries(it.id) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val byId = remember(tracks) { tracks.associateBy { it.id } }
    val ordered = remember(entries, byId) { entries.mapNotNull { byId[it.trackId] } }
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CollectionHeader(selected?.name ?: l("Playlists"),
                    if (selected == null) stringResource(R.string.collections_count, playlists.size)
                    else stringResource(R.string.songs_count, ordered.size))
            }
            IconButton(onClick = { if (selected == null) create = true else add = true },
                modifier = Modifier.padding(end = 18.dp)) { Icon(Icons.Default.Add, l("Add"), tint = accent) }
            if (selected != null) IconButton(onClick = { confirmDelete = true },
                modifier = Modifier.padding(end = 12.dp)) {
                Icon(Icons.Default.Delete, l("Delete"), tint = muted)
            }
        }
        if (selected != null) {
            Row {
                TextButton(onClick = { selected = null }) { Text(l("All playlists")) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (ordered.isNotEmpty()) onPlay(ordered, ordered.first()) }) {
                    Text(l("Play all"))
                }
            }
            LazyColumn {
                items(ordered, key = { it.id }) { track ->
                    val index = ordered.indexOf(track)
                    var drag by remember(track.id) { mutableFloatStateOf(0f) }
                    val view = LocalView.current
                    CollectionTrack(track, { onPlay(ordered, track) }, Modifier
                        .pointerInput(track.id, entries) {
                            detectDragGesturesAfterLongPress(onDragStart = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }, onDrag = { change, amount ->
                                change.consume()
                                drag += amount.y
                                if (kotlin.math.abs(drag) > 55f) {
                                    val to = (index + if (drag > 0) 1 else -1).coerceIn(0, ordered.lastIndex)
                                    if (to != index) scope.launch {
                                        val ids = ordered.map { it.id }.toMutableList()
                                        val moved = ids.removeAt(index); ids.add(to, moved)
                                        withContext(Dispatchers.IO) {
                                            ids.forEachIndexed { i, id ->
                                                db.playlists().put(PlaylistEntry(selected!!.id, id, i))
                                            }
                                            PlaylistBackup.write(context, db)
                                        }
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    drag = 0f
                                }
                            })
                        }
                        .pointerInput(track.id) {
                            var dx = 0f
                            detectHorizontalDragGestures(onHorizontalDrag = { change, amount ->
                                change.consume(); dx += amount
                            }, onDragEnd = {
                                if (kotlin.math.abs(dx) > 100f) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    scope.launch(Dispatchers.IO) {
                                        db.playlists().remove(selected!!.id, track.id)
                                        PlaylistBackup.write(context, db)
                                    }
                                }
                                dx = 0f
                            })
                        })
                }
            }
        } else LazyColumn {
            items(playlists, key = { it.id }) { playlist ->
                Row(Modifier.fillMaxWidth().clickable { selected = playlist }.padding(22.dp)) {
                    Text(playlist.name, color = white, modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold)
                    Text(l("›"), color = accent)
                }
            }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text(l("New playlist")) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(l("Name")) }) },
        confirmButton = { TextButton(onClick = {
            val savedName = name.trim()
            if (savedName.isNotBlank()) scope.launch(Dispatchers.IO) {
                db.playlists().put(Playlist(name = savedName, createdAt = System.currentTimeMillis()))
                PlaylistBackup.write(context, db)
            }
            name = ""; create = false
        }) { Text(l("Create")) } })
    if (add && selected != null) AlertDialog(onDismissRequest = { add = false },
        title = { Text(l("Add songs")) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(tracks, key = { it.id }) { track ->
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        db.playlists().put(PlaylistEntry(selected!!.id, track.id, entries.size))
                        PlaylistBackup.write(context, db)
                    }
                    add = false
                }) { Text("${track.title} · ${track.artist}", maxLines = 1) }
            }
        } }, confirmButton = { TextButton(onClick = { add = false }) { Text(l("Done")) } })
    if (confirmDelete && selected != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(l("Delete playlist")) },
        text = { Text(selected!!.name) },
        confirmButton = { TextButton(onClick = {
            val id = selected!!.id
            selected = null
            confirmDelete = false
            scope.launch(Dispatchers.IO) {
                db.playlists().clear(id)
                db.playlists().delete(id)
                PlaylistBackup.write(context, db)
            }
        }) { Text(l("Delete")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(l("Cancel")) } })
}

object PlaylistBackup {
    suspend fun write(context: Context, db: MusicDb) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        db.playlists().all().forEach { playlist ->
            val ids = JSONArray()
            db.playlists().entriesOnce(playlist.id).forEach { entry ->
                db.tracks().get(entry.trackId)?.let { track ->
                    ids.put(JSONObject().put("id", track.id).put("title", track.title)
                        .put("artist", track.artist).put("duration", track.durationMs))
                }
            }
            array.put(JSONObject().put("name", playlist.name).put("tracks", ids))
        }
        val bytes = JSONObject().put("version", 1).put("playlists", array).toString().toByteArray()
        context.openFileOutput("playlists-backup.json", Context.MODE_PRIVATE).use { it.write(bytes) }
        // A copy in Downloads survives uninstall and can be selected with Restore on a new install.
        runCatching {
            val resolver = context.contentResolver
            val prefs = context.getSharedPreferences("playlist_backup", Context.MODE_PRIVATE)
            val previous = prefs.getString("uri", null)?.let(Uri::parse)
            val uri = previous?.takeIf { saved ->
                runCatching {
                    requireNotNull(resolver.openOutputStream(saved, "wt")).use { it.write(bytes) }
                }.isSuccess
            } ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "playlists-backup.json")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MyMusic")
                }
                val created = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                requireNotNull(resolver.openOutputStream(created, "wt")).use { it.write(bytes) }
                prefs.edit().putString("uri", created.toString()).apply()
                created
            }
            uri
        }
    }

    suspend fun restore(context: Context, db: MusicDb, text: String) = withContext(Dispatchers.IO) {
        val array = JSONObject(text).getJSONArray("playlists")
        val local = db.tracks().all()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = db.playlists().put(Playlist(name = item.getString("name"),
                createdAt = System.currentTimeMillis()))
            val ids = item.getJSONArray("tracks")
            for (j in 0 until ids.length()) {
                val saved = ids.getJSONObject(j)
                val match = local.firstOrNull { it.id == saved.optLong("id") }
                    ?: local.firstOrNull { it.title.equals(saved.optString("title"), true) &&
                        it.artist.equals(saved.optString("artist"), true) &&
                        kotlin.math.abs(it.durationMs - saved.optLong("duration")) < 2000 }
                if (match != null) db.playlists().put(PlaylistEntry(id, match.id, j))
            }
        }
        write(context, db)
    }
}

@Composable fun QueueScreen(player: MediaController?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                revision++
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val items = remember(player, revision) {
        (0 until (player?.mediaItemCount ?: 0)).map { index ->
            index to player!!.getMediaItemAt(index)
        }
    }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 16.dp)) { Text(l("Back")) }
            CollectionHeader(l("Queue"), stringResource(R.string.songs_count, items.size))
        }
        items(items, key = { it.first }) { (index, item) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { player?.seekToDefaultPosition(index) }) {
                    Text(item.mediaMetadata.title?.toString().orEmpty(), color = white, maxLines = 1)
                    Text(item.mediaMetadata.artist?.toString().orEmpty(), color = muted, fontSize = 12.sp)
                }
                TextButton(onClick = { player?.removeMediaItem(index); revision++ }) { Text(l("Remove")) }
            }
        }
    }
}
