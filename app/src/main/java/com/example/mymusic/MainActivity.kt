package com.example.mymusic

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.provider.Settings
import android.provider.MediaStore
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.request.ImageRequest
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

internal val ink = Color(0xFF0B0D12)
internal val panel = Color(0xFF171B24)
internal val raised = Color(0xFF222734)
internal val accent = Color(0xFFFFB86B)
internal val muted = Color(0xFF969EAC)
internal val white = Color(0xFFF4F1EB)

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val db by lazy { MusicDb.get(this) }
    private val metadata by lazy { Metadata(this, db.tracks()) }
    private var controller by mutableStateOf<MediaController?>(null)
    private var message by mutableStateOf("")
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) scan() else message = localized("Allow audio access to see your music")
    }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PlaylistBackup.write(this@MainActivity, db)
                    contentResolver.openOutputStream(uri)?.use { output ->
                        openFileInput("playlists-backup.json").use { it.copyTo(output) }
                    }
                }
            }.onSuccess { message = localized("Playlists exported") }
                .onFailure { message = it.message ?: localized("Export failed") }
        }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Cannot read backup")
                }
                PlaylistBackup.restore(this@MainActivity, db, json)
            }.onSuccess { message = localized("Playlists restored") }
                .onFailure { message = it.message ?: localized("Restore failed") }
        }
    }
    private val deleteDuplicate = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) scan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 13, 18)
        window.navigationBarColor = android.graphics.Color.rgb(11, 13, 18)
        window.decorView.systemUiVisibility = 0
        val future = MediaController.Builder(this, SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))).buildAsync()
        future.addListener({ runCatching { controller = future.get() }.onFailure { message = it.message ?: localized("Playback unavailable") } },
            ContextCompat.getMainExecutor(this))
        requestOrScan()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = ink, surface = panel,
                onPrimary = ink, onBackground = white, onSurface = white)) { Screen() }
        }
    }

    private fun requestOrScan() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) scan() else permission.launch(p)
    }

    private fun scan() = lifecycleScope.launch {
        runCatching { withContext(Dispatchers.IO) { LocalLibrary(this@MainActivity, db.tracks()).scan() } }
            .onSuccess { message = localized("Library updated") }
            .onFailure { message = it.message ?: localized("Scan failed") }
    }

    private fun play(tracks: List<Track>, selected: Track) {
        val player = controller ?: run { message = localized("Player is starting. Try again."); return }
        player.setMediaItems(tracks.map { track ->
            MediaItem.Builder().setMediaId(track.id.toString()).setUri(Uri.parse(track.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                    .setAlbumTitle(track.album).setArtworkUri(track.artUri?.let(Uri::parse)).build()).build()
        }, tracks.indexOf(selected), 0)
        player.prepare()
        player.play()
    }

    @Composable private fun Screen() {
        val tracks by db.tracks().observe().collectAsStateWithLifecycle(emptyList())
        val presets by db.eq().observe().collectAsStateWithLifecycle(emptyList())
        val plays by db.plays().observeSince(0).collectAsStateWithLifecycle(emptyList())
        val player = controller
        var mediaId by remember { mutableStateOf<String?>(null) }
        var playing by remember { mutableStateOf(false) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var page by remember { mutableStateOf("library") }
        var equalizerReturnPage by remember { mutableStateOf("library") }
        var query by remember { mutableStateOf("") }
        var losslessOnly by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var duplicates by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
        var outputOpen by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) {
                    mediaId = p.currentMediaItem?.mediaId
                    playing = p.isPlaying
                    duration = p.duration.coerceAtLeast(0)
                    position = p.currentPosition.coerceAtLeast(0)
                }
            }
            player?.addListener(listener)
            mediaId = player?.currentMediaItem?.mediaId
            playing = player?.isPlaying == true
            onDispose { player?.removeListener(listener) }
        }
        LaunchedEffect(player, playing) {
            while (playing) {
                position = player?.currentPosition?.coerceAtLeast(0) ?: 0
                duration = player?.duration?.coerceAtLeast(0) ?: 0
                delay(500)
            }
        }
        LaunchedEffect(message) {
            if (message.isNotBlank()) { snackbar.showSnackbar(message); message = "" }
        }
        val active = tracks.firstOrNull { it.id.toString() == mediaId }
        val visible = tracks.filter { track ->
            (track.title.contains(query, true) || track.artist.contains(query, true) || track.album.contains(query, true)) &&
                (!losslessOnly || track.isLossless())
        }
        val togglePlay: () -> Unit = { player?.let { if (it.isPlaying) it.pause() else it.play() }; Unit }
        BackHandler(page != "library") {
            page = when (page) {
                "equalizer" -> equalizerReturnPage
                else -> "library"
            }
        }

        Scaffold(containerColor = ink, snackbarHost = {
            SnackbarHost(snackbar) { data -> Snackbar(data, containerColor = raised, contentColor = white) }
        },
            bottomBar = {
                if (page in listOf("library", "for_you", "genres", "playlists", "stats")) {
                    Column {
                        if (active != null) MiniPlayer(active, playing, position, duration,
                            onOpen = { page = "player" }, onPlay = togglePlay,
                            onQueue = { page = "queue" },
                            onPrevious = { player?.seekToPreviousMediaItem() },
                            onNext = { player?.seekToNextMediaItem() },
                            onOutput = { outputOpen = true })
                        NavigationBar(containerColor = ink, tonalElevation = 0.dp) {
                            listOf(
                                Triple("library", Icons.Default.LibraryMusic, l("Library")),
                                Triple("for_you", Icons.Default.AutoAwesome, l("For You")),
                                Triple("genres", Icons.Default.Category, l("Genres")),
                                Triple("playlists", Icons.Default.QueueMusic, l("Playlists")),
                                Triple("stats", Icons.Default.BarChart, l("Stats"))
                            ).forEach { (destination, icon, label) ->
                                NavigationBarItem(selected = page == destination,
                                    onClick = { page = destination },
                                    icon = { Icon(icon, label) }, label = { Text(label, fontSize = 10.sp) },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = ink,
                                        indicatorColor = accent, unselectedIconColor = muted,
                                        unselectedTextColor = muted))
                            }
                        }
                    }
                }
            }) { inner ->
            AnimatedContent(targetState = page, label = "screen", transitionSpec = {
                if (targetState == "player") {
                    (slideInVertically(tween(260)) { it } + fadeIn(tween(260))) togetherWith
                        (fadeOut(tween(160)))
                } else if (initialState == "player") {
                    fadeIn(tween(220)) togetherWith
                        (slideOutVertically(tween(240)) { it } + fadeOut(tween(240)))
                } else fadeIn(tween(200)) togetherWith fadeOut(tween(140))
            }) { screen ->
            when (screen) {
                "player" -> PlayerPage(active, playing, position, duration, busy,
                    onBack = { page = "library" }, onPlay = togglePlay,
                    onPrevious = { player?.seekToPreviousMediaItem() },
                    onNext = { player?.seekToNextMediaItem() },
                    onSeek = { player?.seekTo(it) },
                    onEqualizer = { equalizerReturnPage = "player"; page = "equalizer" },
                    onLyrics = { page = "lyrics" },
                    onEnrich = { active?.let { track -> lifecycleScope.launch {
                        runCatching { metadata.enrich(track) }
                            .onSuccess { message = localized("Track details checked") }
                            .onFailure { message = it.message ?: localized("Metadata lookup failed") }
                    } } },
                    onConvert = { active?.let { track ->
                        busy = true
                        lifecycleScope.launch {
                            runCatching { Conversion(this@MainActivity).toFlac(track) }
                                .onSuccess { message = localized("Verified FLAC saved"); scan() }
                                .onFailure { message = it.message ?: localized("Conversion failed") }
                            busy = false
                        }
                    } }, modifier = Modifier.padding(inner))
                "equalizer" -> EqualizerPage(presets, onBack = { page = equalizerReturnPage },
                    modifier = Modifier.padding(inner))
                "lyrics" -> LyricsPage(active, position, onSeek = { player?.seekTo(it) },
                    onBack = { page = "player" }, modifier = Modifier.padding(inner))
                "settings" -> SettingsPage(onBack = { page = "library" },
                    onDuplicates = { page = "duplicates" }, modifier = Modifier.padding(inner))
                "duplicates" -> DuplicatesScreen(duplicates, onScan = {
                    lifecycleScope.launch {
                        message = localized("Scanning files…")
                        duplicates = DuplicateScanner.scan(this@MainActivity, tracks)
                        message = localized("Duplicate scan complete")
                    }
                }, onDelete = { track ->
                    val request = MediaStore.createDeleteRequest(contentResolver, listOf(Uri.parse(track.uri)))
                    deleteDuplicate.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }, modifier = Modifier.padding(inner))
                "for_you" -> ForYouScreen(tracks, plays, { list, track -> play(list, track) }, Modifier.padding(inner))
                "genres" -> GenresScreen(tracks, { list, track -> play(list, track) },
                    onFetch = {
                        if (!SpotifyCredentials(this@MainActivity).connected) {
                            message = localized("Connect Spotify in Settings")
                        } else lifecycleScope.launch {
                            message = localized("Fetching genres…")
                            tracks.filter { it.genre.isBlank() }.forEach { track ->
                                if (db.tracks().get(track.id)?.genre.isNullOrBlank()) {
                                    runCatching { metadata.enrich(track) }
                                }
                            }
                            message = localized("Genres updated")
                        }
                    }, modifier = Modifier.padding(inner))
                "playlists" -> PlaylistsScreen(db, tracks, { list, track -> play(list, track) }, Modifier.padding(inner))
                "stats" -> StatsScreen(tracks, plays, Modifier.padding(inner))
                "queue" -> QueueScreen(player, onBack = { page = "library" }, modifier = Modifier.padding(inner))
                else -> LibraryPage(visible, tracks.size, query, { query = it }, losslessOnly,
                    { losslessOnly = !losslessOnly },
                    onScan = ::requestOrScan,
                    onSpotify = { page = "settings" },
                    onSettings = { page = "settings" },
                    onEqualizer = { equalizerReturnPage = "library"; page = "equalizer" },
                    onShuffle = { if (visible.isNotEmpty()) { val shuffled = visible.shuffled(); play(shuffled, shuffled.first()) } },
                    onTrack = { play(visible, it) },
                    activeId = mediaId, modifier = Modifier.padding(inner))
            }
            }
        }
        if (outputOpen) {
            val manager = remember { getSystemService(AUDIO_SERVICE) as AudioManager }
            val devices = remember(outputOpen) {
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
                    .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            }
            AlertDialog(onDismissRequest = { outputOpen = false },
                title = { Text(l("Output device")) },
                text = {
                    Column {
                        devices.forEach { device ->
                            TextButton(onClick = {
                                startService(Intent(this@MainActivity, PlaybackService::class.java)
                                    .setAction(PlaybackService.ACTION_ROUTE)
                                    .putExtra("device_id", device.id))
                                outputOpen = false
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(device.productName?.toString()?.ifBlank { l("Phone speaker") }
                                    ?: l("Phone speaker"))
                            }
                        }
                    }
                }, confirmButton = {
                    TextButton(onClick = { outputOpen = false }) { Text(l("Done")) }
                })
        }
    }

    @Composable private fun LibraryPage(
        tracks: List<Track>, total: Int, query: String, onQuery: (String) -> Unit,
        losslessOnly: Boolean, onLossless: () -> Unit, onScan: () -> Unit,
        onSpotify: () -> Unit, onSettings: () -> Unit, onEqualizer: () -> Unit, onShuffle: () -> Unit,
        onTrack: (Track) -> Unit, activeId: String?, modifier: Modifier = Modifier
    ) {
        var searchOpen by remember { mutableStateOf(false) }
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(Icons.Default.Search, l("Search"), tint = white)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEqualizer) { Icon(Icons.Default.Equalizer, l("Equalizer"), tint = white) }
                IconButton(onClick = onScan) { Icon(Icons.Default.Refresh, l("Rescan library"), tint = white) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, l("Settings"), tint = white) }
            }
            if (searchOpen || query.isNotEmpty()) {
            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(54.dp)
                .clip(RoundedCornerShape(17.dp)).background(panel).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = muted)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(l("Search songs, artists, albums"), color = muted, fontSize = 14.sp)
                    BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(
                        color = white, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, l("Clear search"), tint = muted, modifier = Modifier.size(18.dp))
                }
            }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill(l("All songs"), !losslessOnly) { if (losslessOnly) onLossless() }
                FilterPill(l("Lossless"), losslessOnly) { if (!losslessOnly) onLossless() }
            }
            Spacer(Modifier.height(31.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l("Library"), color = white, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.songs_on_device, total), color = muted, fontSize = 13.sp)
                }
                FilledIconButton(onClick = onShuffle, enabled = tracks.isNotEmpty(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = ink),
                    modifier = Modifier.size(46.dp)) { Icon(Icons.Default.Shuffle, l("Shuffle play")) }
            }
            Spacer(Modifier.height(15.dp))
            if (tracks.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.LibraryMusic, null, tint = accent, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(if (total == 0) l("Your library is waiting") else l("No matching songs"), color = white,
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Text(if (total == 0) l("Add music to your phone, then scan again.") else l("Try another search or filter."),
                        color = muted, fontSize = 14.sp)
                    if (total == 0) {
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onScan) { Text(l("Scan music")) }
                    }
                }
            } else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                items(tracks, key = { it.id }) { track -> TrackRow(track, activeId == track.id.toString()) { onTrack(track) } }
                item {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onSpotify, modifier = Modifier.fillMaxWidth()) {
                        Text(l("Connect Spotify for artwork & details"), color = muted)
                    }
                }
            }
        }
    }

    @Composable private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) accent else panel)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, color = if (selected) ink else muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable private fun TrackRow(track: Track, active: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(if (active) panel else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(54.dp), 12.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = if (active) accent else white, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(track.artist, color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTime(track.durationMs), color = muted, fontSize = 12.sp)
                if (track.isLossless()) {
                    Spacer(Modifier.height(5.dp))
                    Text(l("LOSSLESS"), color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp)
                }
            }
        }
    }

    @Composable private fun Artwork(track: Track?, modifier: Modifier, radius: Dp) {
        Box(modifier.clip(RoundedCornerShape(radius)).background(Brush.linearGradient(
            listOf(Color(0xFF5D3D48), Color(0xFF2A3446), Color(0xFF263E40)))),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, l("Album artwork"), tint = accent.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxSize(0.38f))
            if (track?.artUri != null) AsyncImage(model = ImageRequest.Builder(LocalContext.current)
                    .data(track.artUri).crossfade(280).build(), contentDescription = l("Album artwork"),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun MiniPlayer(track: Track, playing: Boolean, position: Long, duration: Long,
        onOpen: () -> Unit, onPlay: () -> Unit, onQueue: () -> Unit,
        onPrevious: () -> Unit, onNext: () -> Unit,
        onOutput: () -> Unit) {
        val view = LocalView.current
        Column(Modifier.fillMaxWidth().background(ink).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(raised)
                .pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                        change.consume(); dy += amount
                    }, onDragEnd = {
                        if (dy < -80f) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onOpen()
                        }
                        dy = 0f
                    })
                }
                .combinedClickable(onClick = onOpen, onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onQueue()
                }).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(46.dp), 10.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = white, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(37.dp)) {
                    Icon(Icons.Default.SkipPrevious, l("Previous"), tint = white)
                }
                IconButton(onClick = onPlay, modifier = Modifier.size(37.dp)) {
                    AnimatedContent(targetState = playing, label = "mini play") { isPlaying ->
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) l("Pause") else l("Play"), tint = white)
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(37.dp)) {
                    Icon(Icons.Default.SkipNext, l("Next"), tint = white)
                }
                IconButton(onClick = onOutput, modifier = Modifier.size(37.dp)) {
                    Icon(Icons.Default.Speaker, l("Output device"), tint = white, modifier = Modifier.size(19.dp))
                }
            }
            LinearProgressIndicator(progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = accent,
                trackColor = Color.Transparent)
        }
    }

    @Composable private fun PlayerPage(track: Track?, playing: Boolean, position: Long, duration: Long,
        busy: Boolean, onBack: () -> Unit, onPlay: () -> Unit, onPrevious: () -> Unit,
        onNext: () -> Unit, onSeek: (Long) -> Unit, onEqualizer: () -> Unit,
        onLyrics: () -> Unit, onEnrich: () -> Unit, onConvert: () -> Unit,
        modifier: Modifier = Modifier) {
        val view = LocalView.current
        val context = LocalContext.current
        val imageLoader = remember { ImageLoader(context) }
        val artworkColor by produceState(initialValue = Color(0xFF303C45), key1 = track?.artUri) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = track?.artUri ?: return@runCatching Color(0xFF303C45)
                    val result = imageLoader.execute(ImageRequest.Builder(context).data(uri).build())
                    val bitmap = result.drawable?.toBitmap() ?: return@runCatching Color(0xFF303C45)
                    Color(Palette.from(bitmap).generate().getDominantColor(0xFF303C45.toInt()))
                }.getOrDefault(Color(0xFF303C45))
            }
        }
        Column(modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(artworkColor.copy(alpha = 0.7f), ink, ink)))
            .pointerInput(Unit) {
            var dy = 0f
            detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                change.consume(); dy += amount
            }, onDragEnd = {
                if (dy > 100f) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onBack()
                }
                dy = 0f
            })
        }
            .padding(horizontal = 25.dp)) {
            Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, l("Back to library"), tint = white) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEqualizer) { Icon(Icons.Default.Equalizer, l("Equalizer"), tint = white) }
            }
            Spacer(Modifier.weight(0.65f))
            AnimatedContent(targetState = track, label = "album art", transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(300))
            }) { current ->
                Artwork(current, Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 12.dp)
                    .pointerInput(current?.id) {
                        var dx = 0f
                        detectHorizontalDragGestures(onHorizontalDrag = { change, amount ->
                            change.consume(); dx += amount
                        }, onDragEnd = {
                            if (kotlin.math.abs(dx) > 85f) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                if (dx < 0) onNext() else onPrevious()
                            }
                            dx = 0f
                        })
                    }, 26.dp)
            }
            Spacer(Modifier.weight(0.8f))
            Text(track?.title ?: l("Nothing playing"), color = white, fontSize = 27.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(track?.artist ?: l("Choose a song from your library"), color = muted, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
            Slider(value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { onSeek(it.roundToLong()) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
                    inactiveTrackColor = raised), enabled = track != null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = muted, fontSize = 12.sp)
                Text(formatTime(duration), color = muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(62.dp)) {
                    Icon(Icons.Default.SkipPrevious, l("Previous"), tint = white, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(25.dp))
                FilledIconButton(onClick = onPlay, modifier = Modifier.size(76.dp), enabled = track != null,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = ink)) {
                    AnimatedContent(targetState = playing, label = "play pause") { isPlaying ->
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) l("Pause") else l("Play"), modifier = Modifier.size(37.dp))
                    }
                }
                Spacer(Modifier.width(25.dp))
                IconButton(onClick = onNext, modifier = Modifier.size(62.dp)) {
                    Icon(Icons.Default.SkipNext, l("Next"), tint = white, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.weight(0.7f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PlayerAction(Icons.Default.FormatAlignLeft, l("Lyrics"), onLyrics)
                PlayerAction(Icons.Default.AutoAwesome, l("Get details"), onEnrich)
                PlayerAction(Icons.Default.GraphicEq, l("Equalizer"), onEqualizer)
            }
            Spacer(Modifier.height(17.dp))
            if (track != null && track.canConvert()) {
                OutlinedButton(onClick = onConvert, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) {
                    Icon(Icons.Default.HighQuality, null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) l("Converting & verifying…") else l("Convert to verified FLAC"))
                }
            }
            Spacer(Modifier.height(13.dp))
        }
    }

    @Composable private fun PlayerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
        Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = accent, modifier = Modifier.size(23.dp))
            Spacer(Modifier.height(7.dp))
            Text(label, color = muted, fontSize = 11.sp)
        }
    }

    @Composable private fun LyricsPage(track: Track?, position: Long, onSeek: (Long) -> Unit,
        onBack: () -> Unit, modifier: Modifier = Modifier) {
        val timed = remember(track?.lyrics) { parseLrc(track?.lyrics.orEmpty()) }
        val active = timed.indexOfLast { it.first <= position }.coerceAtLeast(0)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(active, timed.size) {
            if (timed.isNotEmpty()) listState.animateScrollToItem(active)
        }
        Column(modifier.fillMaxSize()) {
            PageHeader(l("Lyrics"), onBack)
            Text(track?.title ?: "", color = white, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 20.dp))
            Text(track?.artist ?: "", color = muted, modifier = Modifier.padding(start = 26.dp, top = 4.dp))
            LazyColumn(state = listState, contentPadding = PaddingValues(26.dp)) {
                if (timed.isNotEmpty()) items(timed.size) { index ->
                    val (time, line) = timed[index]
                    Text(line, color = if (index == active) accent else muted,
                        fontSize = if (index == active) 23.sp else 19.sp, lineHeight = 31.sp,
                        fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { onSeek(time) }
                            .padding(vertical = 12.dp))
                } else item {
                    Text(track?.lyrics?.ifBlank { null } ?: l("No lyrics yet. Tap Get details on the player to search LRCLIB."),
                        color = if (track?.lyrics.isNullOrBlank()) muted else white,
                        fontSize = 19.sp, lineHeight = 31.sp)
                }
            }
        }
    }

    @Composable private fun PageHeader(title: String, onBack: () -> Unit) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, l("Back"), tint = white) }
            Spacer(Modifier.width(8.dp))
            Text(title, color = white, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable private fun SettingsPage(onBack: () -> Unit, onDuplicates: () -> Unit,
        modifier: Modifier = Modifier) {
        val credentials = remember { SpotifyCredentials(this) }
        val playbackPrefs = remember { getSharedPreferences("playback", MODE_PRIVATE) }
        var clientId by remember { mutableStateOf(credentials.clientId) }
        var clientSecret by remember { mutableStateOf(credentials.clientSecret) }
        var accessToken by remember { mutableStateOf(credentials.accessToken) }
        var connected by remember { mutableStateOf(credentials.connected) }
        var checking by remember { mutableStateOf(false) }
        var crossfade by remember { mutableFloatStateOf(playbackPrefs.getInt("crossfade", 0).toFloat()) }
        var timerMinutes by remember { mutableFloatStateOf(0f) }
        var selectedLanguage by remember { mutableStateOf(AppLanguage.selected(this)) }
        Column(modifier.fillMaxSize()) {
            PageHeader(l("Settings"), onBack)
            LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(l("Language"), color = white, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    listOf("" to l("System default"), "en" to "English", "ru" to "Русский").forEach { (tag, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            selectedLanguage = tag
                            AppLanguage.apply(this@MainActivity, tag)
                            recreate()
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedLanguage == tag, onClick = {
                                selectedLanguage = tag
                                AppLanguage.apply(this@MainActivity, tag)
                                recreate()
                            })
                            Text(label, color = white)
                        }
                    }
                    HorizontalDivider(color = raised)
                }
                item {
                    Text(l("Spotify"), color = white, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(l("Paste an access token, or enter an app client ID and secret."), color = muted)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50))
                            .background(if (connected) Color(0xFF77D6A0) else muted))
                        Spacer(Modifier.width(8.dp))
                        Text(if (connected) l("Connected") else l("Not connected"), color = if (connected) Color(0xFF77D6A0) else muted)
                    }
                }
                item { OutlinedTextField(clientId, { clientId = it }, label = { Text(l("Client ID")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(clientSecret, { clientSecret = it }, label = { Text(l("Client secret")) },
                    singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(accessToken, { accessToken = it }, label = { Text(l("Access token")) },
                    singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()) }
                item {
                    Button(onClick = {
                        checking = true
                        lifecycleScope.launch {
                            runCatching { credentials.validateAndSave(clientId, clientSecret, accessToken) }
                                .onSuccess { connected = it; message = if (it) localized("Spotify connected") else localized("Spotify rejected the token") }
                                .onFailure { message = it.message ?: localized("Spotify validation failed") }
                            checking = false
                        }
                    }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                        Text(if (checking) l("Checking…") else l("Validate & save"))
                    }
                    TextButton(onClick = {
                        credentials.clear(); connected = false
                        clientId = ""; clientSecret = ""; accessToken = ""
                    }) { Text(l("Disconnect")) }
                    HorizontalDivider(color = raised)
                    Spacer(Modifier.height(20.dp))
                    Text(l("Playback"), color = white, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.crossfade_seconds, crossfade.toInt()), color = white)
                    Slider(crossfade, {
                        crossfade = it
                        playbackPrefs.edit().putInt("crossfade", it.toInt()).apply()
                    }, valueRange = 0f..12f, steps = 11)
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.timer_minutes, timerMinutes.toInt()), color = white)
                    Slider(timerMinutes, { timerMinutes = it }, valueRange = 0f..120f, steps = 11)
                    Button(onClick = {
                        val end = if (timerMinutes > 0f) System.currentTimeMillis() + timerMinutes.toLong() * 60_000 else 0L
                        playbackPrefs.edit().putLong("sleep_until", end).apply()
                        message = if (end == 0L) localized("Sleep timer off") else localized("Sleep timer set")
                    }, modifier = Modifier.fillMaxWidth()) { Text(l("Set sleep timer")) }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = raised)
                    Spacer(Modifier.height(20.dp))
                    Text(l("Playlist backup"), color = white, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(l("A local backup is saved automatically when playlists change."), color = muted)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { exportBackup.launch("mymusic-playlists.json") },
                        modifier = Modifier.fillMaxWidth()) { Text(l("Export JSON")) }
                    OutlinedButton(onClick = { importBackup.launch(arrayOf("application/json")) },
                        modifier = Modifier.fillMaxWidth()) { Text(l("Restore JSON")) }
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = onDuplicates, modifier = Modifier.fillMaxWidth()) {
                        Text(l("Find duplicate files"))
                    }
                }
            }
        }
    }

    @Composable private fun EqualizerPage(saved: List<EqPreset>, onBack: () -> Unit, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        var update by remember { mutableIntStateOf(0) }
        var name by remember { mutableStateOf("") }
        val count = AudioEffects.bandCount()
        val range = AudioEffects.range()
        Column(modifier.fillMaxSize()) {
            PageHeader(l("Equalizer"), onBack)
            LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp)) {
                item {
                    Text(l("Shape your sound"), color = white, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(l("Tune the music to your ears."), color = muted, fontSize = 14.sp)
                    Spacer(Modifier.height(27.dp))
                    if (count == 0) {
                        Text(l("Start playing a song to activate audio effects."), color = muted)
                    } else {
                        Text(l("PRESETS"), color = accent, fontSize = 11.sp, letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AudioEffects.builtInPresets().size) { index ->
                                FilterPill(AudioEffects.builtInPresets()[index], false) {
                                    AudioEffects.usePreset(context, index); update++
                                }
                            }
                            items(saved) { preset ->
                                FilterPill(preset.name, false) {
                                    preset.levels.split(",").forEachIndexed { i, value ->
                                        if (i < count) AudioEffects.setBand(context, i, value.toIntOrNull() ?: 0)
                                    }
                                    AudioEffects.setBass(context, preset.bass); update++
                                }
                            }
                        }
                        Spacer(Modifier.height(29.dp))
                        Text(l("FREQUENCIES"), color = accent, fontSize = 11.sp, letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                    }
                }
                items(count) { i ->
                    key(update, i) {
                        var level by remember { mutableFloatStateOf(AudioEffects.level(i).toFloat()) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${AudioEffects.frequency(i)} Hz", color = white, fontSize = 13.sp,
                                modifier = Modifier.width(67.dp))
                            Slider(value = level, onValueChange = {
                                level = it; AudioEffects.setBand(context, i, it.toInt())
                            }, valueRange = range.first.toFloat()..range.last.toFloat(),
                                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
                                    inactiveTrackColor = raised), modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (count > 0) item {
                    key(update) {
                        var bass by remember { mutableFloatStateOf(AudioEffects.bass().toFloat()) }
                        Spacer(Modifier.height(18.dp))
                        Text(l("BASS BOOST"), color = accent, fontSize = 11.sp, letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold)
                        Slider(value = bass, onValueChange = {
                            bass = it; AudioEffects.setBass(context, it.toInt())
                        }, valueRange = 0f..1000f,
                            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
                                inactiveTrackColor = raised))
                    }
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text(l("Custom preset name")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(13.dp))
                    Button(enabled = name.isNotBlank(), onClick = {
                        lifecycleScope.launch {
                            db.eq().put(EqPreset(name.trim(), (0 until count).joinToString(",") {
                                AudioEffects.level(it).toString() }, AudioEffects.bass()))
                            name = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(l("Save preset")) }
                }
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

private fun Track.isLossless(): Boolean = mime.contains("flac", true) || mime.contains("wav", true) ||
    mime.contains("alac", true) || mime.contains("aiff", true)

private fun Track.canConvert(): Boolean = mime.contains("wav", true) || mime.contains("alac", true) ||
    mime.contains("mp4", true) || mime.contains("m4a", true)

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun parseLrc(value: String): List<Pair<Long, String>> {
    val pattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")
    return value.lineSequence().mapNotNull { line ->
        val match = pattern.find(line) ?: return@mapNotNull null
        val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        ((minute * 60 + second) * 1000 + fraction) to line.substring(match.range.last + 1).trim()
    }.filter { it.second.isNotEmpty() }.sortedBy { it.first }.toList()
}
