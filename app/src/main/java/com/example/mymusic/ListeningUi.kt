package com.example.mymusic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun RootNavigationBar(page: String, onDestination: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(ink).navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("library", Icons.Default.MusicNote, l("Library")),
            Triple("playlists", Icons.Default.QueueMusic, l("Playlists")),
            Triple("audiobooks", Icons.Default.Headphones, l("Audiobooks"))).forEach { (destination, icon, label) ->
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onDestination(destination) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, label, tint = if (page == destination) accent else muted, modifier = Modifier.size(25.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = if (page == destination) accent else muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable internal fun LibraryShortcuts(likedCount: Int, onLiked: () -> Unit, onStats: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(Triple(Icons.Default.Favorite, l("Liked tracks"), onLiked),
            Triple(Icons.Default.Insights, l("Statistics"), onStats)).forEachIndexed { index, (icon, title, action) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(raised)
                .clickable(onClick = action).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = if (index == 0) androidx.compose.ui.graphics.Color(0xFFFFA6B7) else accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.NorthEast, null, tint = muted, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(title, color = white, fontFamily = headingFont, fontSize = 19.sp, lineHeight = 25.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(if (index == 0) stringResource(R.string.mix_saved_count, likedCount) else l("Your music in numbers"), color = muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable fun LikedTracksScreen(tracks: List<Track>, likedIds: Set<Long>, onPlay: (List<Track>, Track) -> Unit,
    onUnlike: (Long) -> Unit, onHome: () -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val liked = remember(tracks, likedIds) { tracks.filter { !it.isAudiobook && it.id in likedIds } }
    val filtered = remember(liked, query) { liked.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) } }
    val entry = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entry.animateTo(1f, tween(420)) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextButton(onClick = onHome, modifier = Modifier.padding(start = 12.dp)) { Text(l("Back to library")) }
            Column(Modifier.padding(24.dp).graphicsLayer { alpha = entry.value; translationY = (1f - entry.value) * 18.dp.toPx() }) {
                Icon(Icons.Default.Favorite, null, tint = androidx.compose.ui.graphics.Color(0xFFFFA6B7), modifier = Modifier.size(46.dp))
                Spacer(Modifier.height(18.dp))
                Text(l("Liked tracks"), color = white, fontFamily = headingFont, fontSize = 36.sp, lineHeight = 44.sp)
                Spacer(Modifier.height(10.dp))
                Text(l("Every heart in the player saves a song here"), color = muted, fontSize = 14.sp)
                Spacer(Modifier.height(18.dp))
                Button(onClick = { liked.firstOrNull()?.let { onPlay(liked, it) } }, enabled = liked.isNotEmpty()) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(l("Play all"))
                }
            }
            if (liked.isNotEmpty()) OutlinedTextField(query, { query = it }, singleLine = true,
                placeholder = { Text(l("Search liked tracks")) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), shape = RoundedCornerShape(18.dp))
            if (liked.isEmpty()) Text(l("Tap the heart on a song to start your collection"), color = muted, modifier = Modifier.padding(24.dp))
            else if (filtered.isEmpty()) Text(l("No matching songs"), color = muted, modifier = Modifier.padding(24.dp))
        }
        items(filtered, key = { it.id }, contentType = { "likedTrack" }) { track ->
            Row(Modifier.fillMaxWidth().animateItem(fadeInSpec = tween(180), placementSpec = tween(240), fadeOutSpec = tween(140)), verticalAlignment = Alignment.CenterVertically) {
                CollectionTrack(track, { onPlay(filtered, track) }, Modifier.weight(1f))
                IconButton(onClick = { onUnlike(track.id) }, modifier = Modifier.padding(end = 12.dp)) {
                    Icon(Icons.Default.Favorite, l("Remove from liked tracks"), tint = androidx.compose.ui.graphics.Color(0xFFFFA6B7))
                }
            }
        }
    }
}

@Composable fun StatsScreen(tracks: List<Track>, plays: List<PlayEvent>, onBack: () -> Unit,
    onPlay: (List<Track>, Track) -> Unit, modifier: Modifier = Modifier) {
    var month by remember { mutableStateOf(false) }
    var insights by remember { mutableStateOf(ListeningInsights()) }
    LaunchedEffect(tracks, plays, month) {
        insights = withContext(Dispatchers.Default) { listeningInsights(tracks, plays, if (month) 30 else 7) }
    }
    val entry = remember { Animatable(0f) }
    LaunchedEffect(insights, month) { entry.snapTo(0f); entry.animateTo(1f, tween(650)) }
    val color = accent
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) { Text(l("Back")) }
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(l("Your listening story"), color = white, fontFamily = headingFont, fontSize = 35.sp, lineHeight = 42.sp)
                Spacer(Modifier.height(8.dp))
                Text(l("Little discoveries in your everyday soundtrack"), color = muted, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(!month, { month = false }, label = { Text(l("Week")) })
                    FilterChip(month, { month = true }, label = { Text(l("Month")) })
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(insights.starts.toString(), l("Song starts"), Modifier.weight(1f))
                    MetricCard(insights.uniqueTracks.toString(), l("Different tracks"), Modifier.weight(1f))
                }
                Text(l("Your rhythm"), color = white, fontFamily = headingFont, fontSize = 24.sp)
                Spacer(Modifier.height(14.dp))
                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                    val max = insights.days.maxOfOrNull { it.starts }?.coerceAtLeast(1) ?: 1
                    val step = size.width / insights.days.size.coerceAtLeast(1)
                    insights.days.forEachIndexed { index, day ->
                        val height = (size.height - 6.dp.toPx()) * day.starts / max * entry.value
                        drawRoundRect(color.copy(alpha = if (day.starts > 0) 1f else .15f),
                            Offset(step * index + step * .15f, size.height - height.coerceAtLeast(3.dp.toPx())),
                            Size(step * .7f, height.coerceAtLeast(3.dp.toPx())), CornerRadius(3.dp.toPx()))
                    }
                }
                Text(l("Daily song starts, oldest to newest"), color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(24.dp))
                AnimatedVisibility(insights.starts > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        InsightCard("✦", stringResource(R.string.mix_artist_fact, insights.uniqueArtists), l("Different voices in your rotation"))
                        InsightCard("↗", stringResource(R.string.mix_discovery_fact, insights.discoveryPercent), l("Tracks absent from earlier history within the last 90 days"))
                        InsightCard("◷", stringResource(R.string.mix_hour_fact, insights.peakHour ?: 0), l("The hour you most often start a song"))
                        InsightCard("☀", stringResource(R.string.mix_streak_fact, insights.longestStreak), stringResource(R.string.mix_active_days, insights.activeDays))
                    }
                }
                if (insights.starts == 0) InsightCard("♫", l("Your story is just beginning"), l("Play music and your patterns will appear here"))
                Spacer(Modifier.height(22.dp))
                Text(l("Song starts are counted, not minutes listened"), color = muted, fontSize = 11.sp)
                Spacer(Modifier.height(28.dp))
                Text(l("TOP TRACKS"), color = color, fontSize = 11.sp, letterSpacing = 2.sp)
            }
        }
        items(insights.topTracks, key = { it.first.id }, contentType = { "statTrack" }) { (track, count) ->
            Column(Modifier.fillMaxWidth().clickable { onPlay(insights.topTracks.map { it.first }, track) }.padding(horizontal = 24.dp, vertical = 14.dp)) {
                StatLine(track.title, count, insights.topTracks.firstOrNull()?.second ?: 1) { entry.value }
                Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item { Text(l("TOP ARTISTS"), color = color, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(24.dp)) }
        items(insights.topArtists, key = { it.first }, contentType = { "statArtist" }) { (name, count) ->
            Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                StatLine(name, count, insights.topArtists.firstOrNull()?.second ?: 1) { entry.value }
            }
        }
    }
}

@Composable private fun MetricCard(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = .18f), panel))).padding(18.dp)) {
        Text(value, color = accent, fontFamily = headingFont, fontSize = 35.sp)
        Text(label, color = muted, fontSize = 12.sp)
    }
}

@Composable private fun InsightCard(symbol: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(panel).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, color = accent, fontSize = 26.sp, modifier = Modifier.width(42.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = white, fontFamily = headingFont, fontSize = 20.sp, lineHeight = 26.sp)
            Spacer(Modifier.height(5.dp)); Text(detail, color = muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable private fun StatLine(label: String, count: Int, max: Int, progress: () -> Float) {
    Column {
        Row {
            Text(label, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$count", color = accent, modifier = Modifier.padding(start = 10.dp))
        }
        LinearProgressIndicator(progress = { count.toFloat() / max.coerceAtLeast(1) * progress() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp).height(5.dp).clip(RoundedCornerShape(50)), color = accent, trackColor = raised)
    }
}
