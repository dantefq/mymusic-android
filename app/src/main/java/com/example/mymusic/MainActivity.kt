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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.withTransaction
import coil.compose.AsyncImage
import coil.Coil
import coil.request.ImageRequest
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val db by lazy { MusicDb.get(this) }
    private val metadata by lazy { Metadata(this, db.tracks()) }
    private var controller by mutableStateOf<MediaController?>(null)
    private var activityDestroyed = false
    private var scanJob: Job? = null
    private var playJob: Job? = null
    private var duplicateJob: Job? = null
    private var duplicateGroups by mutableStateOf<List<DuplicateGroup>?>(null)
    private var duplicateScanning by mutableStateOf(false)
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
    private val deleteDuplicate = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pendingPrefs = getSharedPreferences("duplicate_cleanup", MODE_PRIVATE)
        val mapping = pendingPrefs.getStringSet("pending", emptySet()).orEmpty().mapNotNull { item ->
            val ids = item.split(':').mapNotNull(String::toLongOrNull)
            if (ids.size == 2) ids[0] to ids[1] else null
        }
        pendingPrefs.edit().remove("pending").apply()
        if (result.resultCode == RESULT_OK) lifecycleScope.launch {
            val kept = withContext(Dispatchers.IO) {
                db.withTransaction {
                    mapping.forEach { (remove, keep) ->
                        db.playlists().copyTrackReferences(remove, keep)
                        db.playlists().removeTrackReferences(remove)
                        db.plays().replaceTrack(remove, keep)
                    }
                }
                mapping.associate { (remove, keep) -> remove to db.tracks().get(keep) }
            }
            controller?.let { player ->
                repeat(player.mediaItemCount) { index ->
                    kept[player.getMediaItemAt(index).mediaId.toLongOrNull()]?.let { player.replaceMediaItem(index, mediaItem(it)) }
                }
            }
            duplicateGroups = null
            scan(allowAutoCleanup = false)
            withContext(Dispatchers.IO) { PlaylistBackup.write(applicationContext, db) }
            message = localized("Duplicates removed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) { deleteSharedPreferences("spotify_secure") }
        window.statusBarColor = android.graphics.Color.rgb(12, 16, 24)
        window.navigationBarColor = android.graphics.Color.rgb(12, 16, 24)
        window.decorView.systemUiVisibility = 0
        val future = MediaController.Builder(this, SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))).buildAsync()
        future.addListener({ runCatching { future.get() }
            .onSuccess { if (activityDestroyed) it.release() else controller = it }
            .onFailure { if (!activityDestroyed) message = it.message ?: localized("Playback unavailable") } },
            ContextCompat.getMainExecutor(this))
        requestOrScan()
        setContent {
            MyMusicTheme {
                val background = ink
                SideEffect {
                    window.statusBarColor = background.toArgb()
                    window.navigationBarColor = background.toArgb()
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = background.luminance() > .45f
                        isAppearanceLightNavigationBars = background.luminance() > .45f
                    }
                }
                Screen()
            }
        }
    }

    private fun requestOrScan(force: Boolean = false) {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) scan(force) else permission.launch(p)
    }

    private fun scan(force: Boolean = false, allowAutoCleanup: Boolean = true) {
        if (scanJob?.isActive == true) return
        scanJob = lifecycleScope.launch {
        runCatching { withContext(Dispatchers.IO) { LocalLibrary(applicationContext, db.tracks()).scan(force) } }
            .onSuccess {
                message = localized("Library updated")
                lifecycleScope.launch { metadata.enrichMissing() }
                if (allowAutoCleanup && getSharedPreferences("duplicate_cleanup", MODE_PRIVATE).getBoolean("automatic", false)) scanDuplicates(true)
            }
            .onFailure { message = it.message ?: localized("Scan failed") }
        }
    }

    private fun scanDuplicates(automatic: Boolean = false) {
        if (duplicateJob?.isActive == true) return
        duplicateJob = lifecycleScope.launch {
            duplicateScanning = true
            try {
                val activeId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
                val result = withContext(Dispatchers.IO) {
                    val playlistIds = db.playlists().all().flatMap { db.playlists().entriesOnce(it.id) }.map { it.trackId }.toSet()
                    val groups = DuplicateScanner.scan(applicationContext, db.tracks().all())
                    val plan = duplicateCleanupPlan(groups, activeId, playlistIds)
                    val keepIds = plan.map { it.keep.id }.toSet()
                    groups.map { group -> group.copy(tracks = group.tracks.sortedByDescending { it.id in keepIds }) } to plan
                }
                duplicateGroups = result.first
                if (automatic) requestDuplicateDelete(result.second)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                message = error.message ?: localized("Scan failed")
            } finally { duplicateScanning = false }
        }
    }

    private fun requestDuplicateDelete(plan: List<DuplicateReplacement>) {
        if (plan.isEmpty()) { message = localized("No exact duplicates found"); return }
        if (Build.VERSION.SDK_INT < 30) {
            message = getString(R.string.duplicate_cleanup_android11)
            return
        }
        // A bounded batch avoids oversized Android permission requests; every hash retains a copy.
        val batch = plan.take(150)
        runCatching {
            val request = MediaStore.createDeleteRequest(contentResolver, batch.map { Uri.parse(it.remove.uri) })
            getSharedPreferences("duplicate_cleanup", MODE_PRIVATE).edit()
                .putStringSet("pending", batch.map { "${it.remove.id}:${it.keep.id}" }.toSet()).apply()
            deleteDuplicate.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure { message = it.message ?: localized("Delete failed") }
    }

    private fun play(tracks: List<Track>, selected: Track, mode: String = "off") {
        val player = controller ?: run { message = localized("Player is starting. Try again."); return }
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            val (items, index) = withContext(Dispatchers.Default) {
                val queue = if (mode == "random") listOf(selected) + randomContinuation(tracks, selected.id) else tracks
                queue.map(::mediaItem) to queue.indexOfFirst { it.id == selected.id }
            }
            if (index < 0 || controller !== player) return@launch
            getSharedPreferences("playback", MODE_PRIVATE).edit().putString("queue_mode", mode).apply()
            player.setMediaItems(items, index, 0)
            player.prepare()
            player.play()
        }
    }

    private fun mediaItem(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id.toString()).setUri(Uri.parse(track.uri))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setAlbumTitle(track.album).setArtworkUri(track.artUri?.let(Uri::parse)).build()).build()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Screen() {
        val library by remember {
            db.tracks().observe().distinctUntilChanged().map(::indexLibrary).flowOn(Dispatchers.Default)
        }.collectAsStateWithLifecycle(LibraryIndex())
        val tracks = library.tracks
        val presets by remember { db.eq().observe() }.collectAsStateWithLifecycle(emptyList())
        val plays by remember { db.plays().observeSince(System.currentTimeMillis() - 90L * 86400000) }.collectAsStateWithLifecycle(emptyList())
        var playCounts by remember { mutableStateOf(emptyMap<Long, Int>()) }
        LaunchedEffect(plays) {
            playCounts = withContext(Dispatchers.Default) { plays.groupingBy { it.trackId }.eachCount() }
        }
        val player = controller
        val clock = rememberPlaybackClock(player)
        var mediaId by remember { mutableStateOf<String?>(null) }
        var playing by remember { mutableStateOf(false) }
        var shuffled by remember { mutableStateOf(false) }
        var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
        val navigation = rememberSaveable(saver = PlayerNavigation.Saver) { PlayerNavigation() }
        val page = navigation.page
        var equalizerReturnPage by rememberSaveable { mutableStateOf("library") }
        var query by remember { mutableStateOf("") }
        var settledQuery by remember { mutableStateOf("") }
        var autoDelete by remember { mutableStateOf(getSharedPreferences("duplicate_cleanup", MODE_PRIVATE).getBoolean("automatic", false)) }
        var outputOpen by remember { mutableStateOf(false) }

        var selectedAlbum by rememberSaveable { mutableStateOf("") }
        var selectedArtist by rememberSaveable { mutableStateOf("") }
        val snackbar = remember { SnackbarHostState() }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) {
                    mediaId = p.currentMediaItem?.mediaId
                    playing = p.isPlaying
                    shuffled = p.shuffleModeEnabled
                    repeatMode = p.repeatMode
                }
            }
            player?.addListener(listener)
            mediaId = player?.currentMediaItem?.mediaId
            playing = player?.isPlaying == true
            shuffled = player?.shuffleModeEnabled == true
            repeatMode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
            onDispose { player?.removeListener(listener) }
        }
        LaunchedEffect(message) {
            if (message.isNotBlank()) { snackbar.showSnackbar(message); message = "" }
        }
        LaunchedEffect(query) {
            delay(220)
            settledQuery = query
        }
        val active = library.byId[mediaId?.toLongOrNull()]
        val contentPage = navigation.contentPage
        val openAlbum: (Track) -> Unit = {
            selectedAlbum = albumKey(it)
            navigation.openCollection("album")
        }
        val openArtist: (String) -> Unit = {
            selectedArtist = it
            navigation.openCollection("artist")
        }
        val collectionBack: () -> Unit = navigation::closeCollection
        val togglePlay: () -> Unit = { player?.let { if (it.isPlaying) it.pause() else it.play() }; Unit }
        val playNormal: (List<Track>, Track) -> Unit = { list, track ->
            if (settledQuery.isNotBlank()) {
                play(tracks, track, "random")
            } else play(list, track)
        }
        BackHandler(page != "library") {
            navigation.back(equalizerReturnPage)
        }

        Box(Modifier.fillMaxSize()) {
        Scaffold(modifier = if (page == "player") Modifier.clearAndSetSemantics {} else Modifier,
            containerColor = ink, snackbarHost = {
            SnackbarHost(snackbar) { data -> Snackbar(data, containerColor = raised, contentColor = white) }
        },
            bottomBar = {
                if (contentPage in listOf("library", "audiobooks", "playlists", "album", "artist")) {
                    Column {
                        if (active != null) MiniPlayer(active, playing, clock,
                            onOpen = navigation::openPlayer, onPlay = togglePlay,
                            onQueue = { navigation.page = "queue" },
                            onOutput = { outputOpen = true },
                            onPrevious = { player?.seekToPreviousMediaItem() },
                            onNext = { player?.seekToNextMediaItem() })
                        ConceptDivider()
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(
                                Triple("library", Icons.Default.MusicNote, l("Library")),
                                Triple("playlists", Icons.Default.QueueMusic, l("Playlists")),
                                Triple("audiobooks", Icons.Default.Headphones, l("Audiobooks"))
                            ).forEach { (destination, icon, label) ->
                                Column(Modifier.weight(1f).fillMaxHeight().clickable { navigation.page = destination },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(icon, label, tint = if (contentPage == destination) accent else muted, modifier = Modifier.size(25.dp))
                                    Spacer(Modifier.height(4.dp))
                                    Text(label, color = if (contentPage == destination) accent else muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }) { inner ->
            AnimatedContent(targetState = contentPage, label = "screen", transitionSpec = {
                (fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(160)))
                    .using(SizeTransform(sizeAnimationSpec = { _, _ -> tween(0) }))
            }) { screen ->
            when (screen) {
                "equalizer" -> EqualizerPage(presets, onBack = { navigation.page = equalizerReturnPage },
                    modifier = Modifier.padding(inner))
                "appearance" -> Column(Modifier.padding(inner).fillMaxSize()) {
                    Row(Modifier.height(56.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navigation.page = "settings" }) { Icon(Icons.Default.ArrowBack, l("Back")) }
                    }
                    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) { item { AppearanceSettings() } }
                }
                "settings" -> SettingsPage(onBack = { navigation.page = "library" }, onAppearance = { navigation.page = "appearance" },

                    onDuplicates = { navigation.page = "duplicates" }, onStats = { navigation.page = "stats" },
                    modifier = Modifier.padding(inner))
                "duplicates" -> DuplicatesScreen(duplicateGroups, onScan = { scanDuplicates() },
                    onCleanAll = { scanDuplicates(true) }, autoDelete = autoDelete, scanning = duplicateScanning,
                    onAutoDelete = { enabled ->
                        autoDelete = enabled
                        getSharedPreferences("duplicate_cleanup", MODE_PRIVATE).edit().putBoolean("automatic", enabled).apply()
                        if (enabled) scanDuplicates(true)
                    }, onDelete = { track ->
                        val group = duplicateGroups.orEmpty().firstOrNull { track in it.tracks }
                        group?.tracks?.firstOrNull()?.let { keep -> requestDuplicateDelete(listOf(DuplicateReplacement(track, keep))) }
                    }, modifier = Modifier.padding(inner))
                "audiobooks" -> AudiobooksScreen(tracks, { list, track -> play(list, track) },
                    modifier = Modifier.padding(inner))
                "playlists" -> PlaylistsScreen(db, tracks, playNormal, modifier = Modifier.padding(inner))
                "stats" -> StatsScreen(tracks, plays, onBack = { navigation.page = "settings" },
                    modifier = Modifier.padding(inner))
                "queue" -> QueueScreen(player, onBack = { navigation.page = "library" }, modifier = Modifier.padding(inner))
                "album" -> AlbumPage(library.albums[selectedAlbum].orEmpty(),
                    onArtist = openArtist,
                    onBack = collectionBack, onPlay = { list, track -> play(list, track) },
                    modifier = Modifier.padding(inner))
                "artist" -> ArtistPage(selectedArtist, library.artist(selectedArtist),
                    playCounts, onBack = collectionBack, onPlay = { list, track -> play(list, track) },
                    onArtist = openArtist,
                    onAlbum = { selectedAlbum = it; navigation.page = "album" },
                    modifier = Modifier.padding(inner))
                else -> LibraryPage(library.music, library.music.size, query, settledQuery, { query = it },
                    onArtist = { openArtist(it); query = "" },
                    onAlbumTrack = openAlbum,
                    onSmartPlay = {
                        lifecycleScope.launch {
                            val mix = withContext(Dispatchers.Default) { myWaveMix(tracks.filterNot { it.isAudiobook }, plays, kotlin.random.Random.nextInt()).take(20) }
                            if (mix.isNotEmpty()) play(mix, mix.first(), "smart")
                        }
                    },
                    onScan = { requestOrScan(true) },
                    onOutput = { outputOpen = true },
                    onSettings = { navigation.page = "settings" },
                    onShuffle = { list -> if (list.isNotEmpty()) { val shuffled = list.shuffled(); playNormal(shuffled, shuffled.first()) } },
                    onTrack = playNormal,
                    activeId = mediaId, inputEnabled = page != "player", modifier = Modifier.padding(inner))
            }
            }
        }
        AnimatedVisibility(visible = navigation.page == "player", modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it }) {
            PlayerPage(active, playing, clock,
                shuffled = shuffled, repeatMode = repeatMode,
                onShuffle = { player?.shuffleModeEnabled = !shuffled },
                onRepeat = { player?.repeatMode = (repeatMode + 1) % 3 },
                onOutput = { outputOpen = true },
                onAlbumTrack = { active?.let(openAlbum) }, onArtist = openArtist,
                onBack = navigation::closePlayer, onPlay = togglePlay,
                onPrevious = { player?.seekToPreviousMediaItem() }, onNext = { player?.seekToNextMediaItem() },
                onSeek = { player?.seekTo(it) },
                onEqualizer = { equalizerReturnPage = "player"; navigation.page = "equalizer" })
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

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun LibraryPage(
        libraryTracks: List<Track>, total: Int, query: String, settledQuery: String, onQuery: (String) -> Unit,
        onSmartPlay: () -> Unit, onScan: () -> Unit, onOutput: () -> Unit,
        onAlbumTrack: (Track) -> Unit, onArtist: (String) -> Unit,
        onSettings: () -> Unit, onShuffle: (List<Track>) -> Unit,
        onTrack: (List<Track>, Track) -> Unit, activeId: String?, inputEnabled: Boolean, modifier: Modifier = Modifier
    ) {
        var searchOpen by remember { mutableStateOf(false) }
        var toolsOpen by remember { mutableStateOf(false) }
        var searchType by remember { mutableStateOf("All") }

        var tracksExpanded by rememberSaveable { mutableStateOf(false) }
        var artistsExpanded by rememberSaveable { mutableStateOf(false) }

        val preferences = remember { getSharedPreferences("library_view", MODE_PRIVATE) }
        var sortField by remember { mutableStateOf(preferences.getString("sort", "Name").orEmpty()) }
        var descending by remember { mutableStateOf(preferences.getBoolean("descending", false)) }
        var lossless by remember { mutableStateOf(preferences.getBoolean("lossless", false)) }
        var sortOpen by remember { mutableStateOf(false) }
        val browse by produceState(initialValue = LibraryBrowse(), libraryTracks, settledQuery, sortField, descending, lossless) {
            value = withContext(Dispatchers.Default) { browseLibrary(libraryTracks, settledQuery, sortField, descending, lossless) }
        }
        val tracks = browse.tracks
        if (sortOpen) SortSheet(sortField, descending, lossless, onDismiss = { sortOpen = false }) { field, reverse, onlyLossless ->
            sortField = field; descending = reverse; lossless = onlyLossless; sortOpen = false
            preferences.edit().putString("sort", field).putBoolean("descending", reverse).putBoolean("lossless", onlyLossless).apply()
        }
        val artists = browse.artists
        val albums = browse.albums
        BackHandler(searchOpen && inputEnabled) { searchOpen = false; onQuery("") }
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {

                Spacer(Modifier.weight(1f))
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) onQuery("") }) { Icon(Icons.Default.Search, l("Search"), tint = white) }
                Box {
                    IconButton(onClick = onSettings, modifier = Modifier.combinedClickable(onClick = onSettings, onLongClick = { toolsOpen = true })) {
                        Icon(Icons.Default.Settings, l("Settings"), tint = white)
                    }
                    DropdownMenu(toolsOpen, onDismissRequest = { toolsOpen = false }) {
                        DropdownMenuItem(text = { Text(l("Rescan library")) }, onClick = { toolsOpen = false; onScan() })
                        DropdownMenuItem(text = { Text(l("Output device")) }, onClick = { toolsOpen = false; onOutput() })
                    }
                }
            }
            AnimatedVisibility(searchOpen || query.isNotBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    OutlinedTextField(query, onQuery, singleLine = true, placeholder = { Text(l("Search songs, artists, albums")) },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(18.dp),
                        trailingIcon = { IconButton(onClick = { onQuery(""); searchOpen = false }) { Icon(Icons.Default.Close, l("Clear search")) } },
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = panel, focusedContainerColor = panel,
                            unfocusedBorderColor = raised, focusedBorderColor = accent),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("All", "Tracks", "Artists", "Albums")) { label -> FilterPill(l(label), searchType == label) { searchType = label } }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                state = androidx.compose.foundation.lazy.rememberLazyListState()) {
                if (query.isNotBlank()) {
                    if (searchType in listOf("All", "Tracks")) {
                        item("top_result") { Text(l("Top result"), fontFamily = headingFont, fontSize = 23.sp, color = white) }
                        items(tracks.take(1), key = { "top_${it.id}" }) { track -> TrackRow(track, activeId == track.id.toString()) { onTrack(tracks, track) } }
                        item("songs") { Spacer(Modifier.height(16.dp)); Text(l("Songs"), fontFamily = headingFont, fontSize = 23.sp, color = white) }
                        items(tracks.drop(1), key = { "search_${it.id}" }, contentType = { "track" }) { track ->
                            TrackRow(track, activeId == track.id.toString()) { onTrack(tracks, track) }
                        }
                    }
                    if (searchType == "Artists") items(artists, key = { it.name }, contentType = { "artist" }) { artist ->
                        ArtistTile(artist, Modifier.fillMaxWidth()) { onArtist(artist.name) }
                    }
                    if (searchType == "Albums") items(albums, key = { it.first }, contentType = { "album" }) { (_, songs) ->
                        Row(Modifier.fillMaxWidth().clickable { onAlbumTrack(songs.first()) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(songs.first(), Modifier.size(64.dp), 6.dp); Spacer(Modifier.width(14.dp))
                            Column { Text(songs.first().album.ifBlank { l("Unknown album") }, color = white, fontFamily = headingFont, fontSize = 20.sp); Text(songs.first().artist, color = muted, fontSize = 13.sp) }
                        }
                    }
                    if (tracks.isEmpty()) item("search_empty") { Text(l("No matching songs"), color = muted, modifier = Modifier.padding(vertical = 20.dp)) }
                } else {
                    item("smart") {
                        SmartMixButton(onSmartPlay, enabled = total > 0)
                        ConceptDivider(Modifier.padding(top = 16.dp))
                    }
                    item("tracks_header") {
                        SectionTitle(l("All Tracks"), tracksExpanded, trackCount(tracks.size),
                            onSort = { sortOpen = true }) { tracksExpanded = !tracksExpanded }
                    }
                    items(if (tracksExpanded) tracks else tracks.take(3), key = { "track_${it.id}" }, contentType = { "track" }) { track ->
                        TrackRow(track, activeId == track.id.toString(), Modifier.animateItem(
                            fadeInSpec = tween(160), placementSpec = tween(260, easing = FastOutSlowInEasing), fadeOutSpec = tween(120))) { onTrack(tracks, track) }
                    }
                    if (tracks.size > 3 && !tracksExpanded) item("show_tracks") {
                        Row(Modifier.fillMaxWidth().height(44.dp).clickable { tracksExpanded = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text(l("Show all") + " ${tracks.size}", color = accent, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, tint = accent)
                        }
                    }
                    if (tracks.isEmpty()) item("empty") {
                        Text(if (total == 0) l("Add music to your phone, then scan again.") else l("No matching songs"), color = muted, modifier = Modifier.padding(vertical = 24.dp))
                        TextButton(onClick = onScan) { Text(l("Rescan library")) }
                    }
                    item("artists_header") {
                        ConceptDivider(Modifier.padding(top = 8.dp))
                        SectionTitle(l("Artists"), artistsExpanded, artistCount(artists.size)) { artistsExpanded = !artistsExpanded }
                    }
                    items(if (artistsExpanded) browse.artistRows else browse.artistRows.take(1),
                        key = { "artist_${it.first().name}" }, contentType = { "artist_pair" }) { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { artist -> ArtistTile(artist, Modifier.weight(1f)) { onArtist(artist.name) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    @Composable private fun ArtistTile(artist: LibraryArtist, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Row(modifier.clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(artist.songs.firstOrNull(), Modifier.size(72.dp), 50.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(artist.name, color = white, fontFamily = headingFont, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(albumCount(artist.albumCount), color = muted, fontSize = 11.sp)
            }
        }
    }
    @Composable private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) accent else panel)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, color = if (selected) ink else muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable private fun SectionTitle(title: String, expanded: Boolean, count: String = "",
        onSort: (() -> Unit)? = null, onToggle: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontFamily = headingFont, fontSize = 28.sp, color = white, modifier = Modifier.weight(1f).clickable(onClick = onToggle))
                if (onSort != null) IconButton(onClick = onSort) { Icon(Icons.Default.Tune, l("Sort and filter"), tint = white, modifier = Modifier.size(22.dp)) }
                IconButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (expanded) l("Collapse") else l("Show all"), tint = white)
                }
            }
            if (count.isNotBlank()) Eyebrow(count)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun TrackRow(track: Track, active: Boolean, modifier: Modifier = Modifier,
        onClick: () -> Unit) {
        Column(modifier) {
        Row(Modifier.fillMaxWidth()
            .background(if (active) panel else Color.Transparent).clickable(onClick = onClick)
            .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(56.dp), 5.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = if (active) accent else white, fontSize = 16.sp,
                    fontFamily = headingFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                ArtistCredits(track)
            }
            Spacer(Modifier.width(8.dp))
                Text(formatTime(track.durationMs), color = muted, fontSize = 12.sp)
            IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(if (active) Icons.Default.GraphicEq else Icons.Default.PlayArrow, l("Play"), tint = if (active) accent else muted, modifier = Modifier.size(20.dp)) }
        }
        ConceptDivider()
        }
    }

    @Composable private fun AlbumPage(songs: List<Track>, onBack: () -> Unit,
        onArtist: (String) -> Unit,
        onPlay: (List<Track>, Track) -> Unit, modifier: Modifier = Modifier) {
        val first = songs.firstOrNull()
        Box(modifier.fillMaxSize()) {
            Artwork(first, Modifier.fillMaxWidth().height(380.dp), 0.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ink.copy(alpha = .58f), ink), endY = 1000f)))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, l("Back"), tint = white) }
                        Spacer(Modifier.weight(1f))

                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Artwork(first, Modifier.fillMaxWidth(.70f).aspectRatio(1f), 6.dp)
                    }
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(18.dp))
                        Text(first?.album?.ifBlank { l("Unknown album") } ?: l("Unknown album"), color = white,
                            fontFamily = headingFont, fontSize = 34.sp, lineHeight = 38.sp)
                        Text(first?.albumArtist?.ifBlank { first.artist }.orEmpty(), color = white, fontFamily = headingFont, fontSize = 20.sp,
                            modifier = Modifier.clickable { first?.let { onArtist(artistNames(it).firstOrNull().orEmpty()) } }.padding(vertical = 5.dp))
                        Eyebrow(trackCount(songs.size) + " · " + stringResource(R.string.design_minutes_count, (songs.sumOf { it.durationMs } / 60_000).toInt()))
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConceptButton(l("Play"), { first?.let { onPlay(songs, it) } }, Modifier.weight(1f), enabled = first != null,
                                icon = { Icon(Icons.Default.PlayArrow, null) })
                            FilledTonalIconButton(onClick = { songs.shuffled().takeIf { it.isNotEmpty() }?.let { onPlay(it, it.first()) } }, modifier = Modifier.size(48.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = raised, contentColor = white)) { Icon(Icons.Default.Shuffle, l("Shuffle play")) }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                items(songs.size, key = { songs[it].id }, contentType = { "album_track" }) { index ->
                    RankedTrackRow(songs[index], index + 1, false, Modifier.padding(horizontal = 20.dp)) { onPlay(songs, songs[index]) }
                }
            }
        }
    }

    @Composable private fun ArtistPage(name: String, songs: List<Track>, playCounts: Map<Long, Int>, onBack: () -> Unit,
        onPlay: (List<Track>, Track) -> Unit, onAlbum: (String) -> Unit, onArtist: (String) -> Unit,
        modifier: Modifier = Modifier) {
        var browse by remember { mutableStateOf(ArtistBrowse()) }
        LaunchedEffect(songs, name, playCounts) {
            browse = withContext(Dispatchers.Default) { browseArtist(songs, name, playCounts) }
        }
        val albums = browse.albums
        val topTracks = browse.topTracks
        val collaborators = browse.collaborators
        var allTracks by rememberSaveable(name) { mutableStateOf(false) }
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(290.dp)) {
                    Artwork(songs.firstOrNull(), Modifier.fillMaxSize(), 0.dp)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = .3f), ink))))
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, l("Back"), tint = white) }
                        Spacer(Modifier.weight(1f))

                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(name, color = white, fontFamily = headingFont, fontSize = 36.sp, lineHeight = 40.sp)
                        Eyebrow(albumCount(albums.size) + " · " + trackCount(songs.size))
                    }
                }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConceptButton(l("Play"), { songs.firstOrNull()?.let { onPlay(songs, it) } }, Modifier.weight(1f), enabled = songs.isNotEmpty(), icon = { Icon(Icons.Default.PlayArrow, null) })
                    ConceptButton(l("Shuffle"), { songs.shuffled().takeIf { it.isNotEmpty() }?.let { onPlay(it, it.first()) } }, Modifier.weight(1f), primary = false, icon = { Icon(Icons.Default.Shuffle, null) })
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(14.dp))
                    Text(l("Albums"), color = white, fontFamily = headingFont, fontSize = 28.sp)
                    Eyebrow(albumCount(albums.size), Modifier.padding(top = 3.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                    items(albums, key = { it.first }) { (key, tracks) ->
                        Column(Modifier.width(120.dp).clickable { onAlbum(key) }) {
                            Artwork(tracks.first(), Modifier.size(120.dp), 5.dp)
                            Spacer(Modifier.height(5.dp))
                            Text(tracks.first().album.ifBlank { l("Unknown album") }, color = white, fontFamily = headingFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(trackCount(tracks.size), color = muted, fontSize = 11.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l("Top tracks"), color = white, fontFamily = headingFont, fontSize = 26.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { allTracks = !allTracks }) { Text(l(if (allTracks) "Collapse" else "See all"), fontSize = 10.sp, letterSpacing = 1.sp); Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp)) }
                }
            }
            items(if (allTracks) topTracks.size else topTracks.size.coerceAtMost(3), key = { topTracks[it].id }, contentType = { "artist_track" }) { index ->
                RankedTrackRow(topTracks[index], index + 1, true, Modifier.padding(horizontal = 20.dp)) { onPlay(topTracks, topTracks[index]) }
            }
            if (collaborators.isNotEmpty()) {
                item { Text(l("Collaborators"), color = white, fontFamily = headingFont, fontSize = 26.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) }
                items(collaborators, key = { "collaborator_${it.first}" }) { (artist, tracks) ->
                    Row(Modifier.fillMaxWidth().clickable { onArtist(artist) }.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(tracks.first(), Modifier.size(48.dp), 50.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(artist, fontFamily = headingFont, fontSize = 17.sp, color = white); Text(stringResource(R.string.design_featured_count, tracks.size), color = muted, fontSize = 12.sp) }
                        Icon(Icons.Default.ChevronRight, null, tint = white)
                    }
                }
            }
        }
    }

    @Composable private fun RankedTrackRow(track: Track, number: Int, artwork: Boolean, modifier: Modifier = Modifier,
        onClick: () -> Unit) {
        Column(modifier) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$number", color = muted, fontSize = 13.sp, modifier = Modifier.width(26.dp))
                if (artwork) { Artwork(track, Modifier.size(40.dp), 4.dp); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = white, fontFamily = headingFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (artwork || artistNames(track).size > 1) ArtistCredits(track, size = 12)
                }
                Text(formatTime(track.durationMs), color = muted, fontSize = 12.sp)
                IconButton(onClick = onClick) { Icon(Icons.Default.PlayArrow, l("Play"), tint = muted, modifier = Modifier.size(18.dp)) }
            }
            ConceptDivider()
        }
    }
    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun MiniPlayer(track: Track, playing: Boolean, clock: PlaybackClock,
        onOutput: () -> Unit,
        onOpen: () -> Unit, onPlay: () -> Unit, onQueue: () -> Unit,
        onPrevious: () -> Unit, onNext: () -> Unit) {
        val view = LocalView.current
        Column(Modifier.fillMaxWidth().background(panel)) {
            ConceptDivider()
            Row(Modifier.fillMaxWidth()
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
                .pointerInput(track.id) {
                    var dx = 0f
                    detectHorizontalDragGestures(onHorizontalDrag = { change, amount ->
                        change.consume(); dx += amount
                    }, onDragEnd = {
                        if (kotlin.math.abs(dx) > 80f) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (dx < 0) onNext() else onPrevious()
                        }
                        dx = 0f
                    })
                }
                .combinedClickable(onClick = onOpen, onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onQueue()
                }).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(targetState = track, contentKey = { it.id }, modifier = Modifier.weight(1f), label = "mini track",
                    transitionSpec = {
                        ((slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it / 5 } + fadeOut(tween(160))))
                            .using(SizeTransform(sizeAnimationSpec = { _, _ -> tween(0) }))
                    }) { current ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(current, Modifier.size(44.dp), 4.dp)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.title, color = white, fontFamily = headingFont,
                                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current.artist, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, l("Previous"), tint = white)
                }
                IconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(accent), contentAlignment = Alignment.Center) {
                        PlayPauseGlyph(playing, MaterialTheme.colorScheme.onPrimary, Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, l("Next"), tint = white)
                }
                IconButton(onClick = onOutput, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SpeakerGroup, l("Output device"), tint = white, modifier = Modifier.size(21.dp))
                }
            }
            MiniProgressRail(clock)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun PlayerPage(track: Track?, playing: Boolean, clock: PlaybackClock,
        shuffled: Boolean, repeatMode: Int, onShuffle: () -> Unit, onRepeat: () -> Unit,
        onOutput: () -> Unit, onAlbumTrack: () -> Unit, onArtist: (String) -> Unit,
        onBack: () -> Unit, onPlay: () -> Unit, onPrevious: () -> Unit,
        onNext: () -> Unit, onSeek: (Long) -> Unit, onEqualizer: () -> Unit,
        modifier: Modifier = Modifier) {
        val view = LocalView.current
        var showLyrics by remember(track?.id) { mutableStateOf(false) }
        val context = LocalContext.current
        val favorites = remember { context.getSharedPreferences("favorites", MODE_PRIVATE) }
        var favorite by remember(track?.id) { mutableStateOf(track?.id?.toString() in favorites.getStringSet("tracks", emptySet()).orEmpty()) }
        if (showLyrics) ModalBottomSheet(onDismissRequest = { showLyrics = false }, containerColor = panel) {
            Text(l("Lyrics"), fontFamily = headingFont, color = white, fontSize = 28.sp, modifier = Modifier.padding(horizontal = 22.dp))
            LyricTimingControls(track?.id)
            LyricsPanel(track, clock, onSeek, Modifier.fillMaxWidth().fillMaxHeight(.85f))
        }
        BoxWithConstraints(modifier.fillMaxSize().background(ink)) {
            val artSize = minOf(maxWidth - 48.dp, maxHeight * .35f)
            val backgroundColor = ink
            val imageLoader = remember(context) { Coil.imageLoader(context) }
            var artworkColor by remember { mutableStateOf(backgroundColor) }
            LaunchedEffect(track?.artUri, backgroundColor) {
                artworkColor = withContext(Dispatchers.IO) {
                    runCatching {
                        val uri = track?.artUri ?: return@runCatching backgroundColor
                        val result = imageLoader.execute(ImageRequest.Builder(context).data(uri).size(48, 48).allowHardware(false).build())
                        val bitmap = result.drawable?.toBitmap() ?: return@runCatching backgroundColor
                        Color(Palette.from(bitmap).generate().getDominantColor(backgroundColor.toArgb()))
                    }.getOrDefault(backgroundColor)
                }
            }
            val animatedArtworkColor by animateColorAsState(artworkColor, tween(350), label = "artwork color")
            // Palette gradient is cached per artwork; no full-screen GPU blur pass.
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.verticalGradient(listOf(backgroundColor.copy(alpha = .65f),
                    animatedArtworkColor.copy(alpha = .22f), backgroundColor, backgroundColor)))
            })
            Column(Modifier.fillMaxSize().pointerInput(onBack) {
                var dy = 0f
                detectVerticalDragGestures(onVerticalDrag = { change, amount -> change.consume(); dy += amount }, onDragEnd = {
                    if (dy > 100f) { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onBack() }; dy = 0f
                })
            }) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, l("Back to library"), tint = white) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOutput) { Icon(Icons.Default.Cast, l("Output device"), tint = white, modifier = Modifier.size(22.dp)) }
                    IconButton(onClick = onEqualizer) { Icon(Icons.Default.Equalizer, l("Equalizer"), tint = white) }
                }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(targetState = track, contentKey = { it?.id to it?.artUri }, label = "album art", transitionSpec = {
                        (fadeIn(tween(240)) togetherWith fadeOut(tween(180))).using(SizeTransform(sizeAnimationSpec = { _, _ -> tween(0) }))
                    }) { current ->
                        Artwork(current, Modifier.size(artSize).pointerInput(current?.id, onNext, onPrevious) {
                            var dx = 0f
                            detectHorizontalDragGestures(onHorizontalDrag = { change, amount -> change.consume(); dx += amount }, onDragEnd = {
                                if (kotlin.math.abs(dx) > 85f) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (dx < 0) onNext() else onPrevious() }; dx = 0f
                            })
                        }, 7.dp)
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(track?.title ?: l("Nothing playing"), color = white, fontSize = 32.sp, lineHeight = 36.sp, fontFamily = headingFont,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable(onClick = onAlbumTrack))
                        IconButton(onClick = {
                            val id = track?.id?.toString() ?: return@IconButton
                            favorite = !favorite
                            val ids = favorites.getStringSet("tracks", emptySet()).orEmpty().toMutableSet()
                            if (favorite) ids.add(id) else ids.remove(id)
                            favorites.edit().putStringSet("tracks", ids).apply()
                        }, enabled = track != null) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, l("Favorite"), tint = if (favorite) accent else white) }
                    }
                    if (track != null) ArtistLinks(track, onArtist, size = 16)
                    else Text(l("Choose a song from your library"), color = muted)
                    if (track != null) Text(track.album.ifBlank { l("Unknown album") },
                        color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onAlbumTrack).padding(vertical = 5.dp))
                    GlassSeekBar(clock, track?.id ?: 0, track != null, onSeek)
                    Row(Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, l("Shuffle"), tint = if (shuffled) accent else white, modifier = Modifier.size(23.dp)) }
                        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, l("Previous"), tint = white, modifier = Modifier.size(32.dp)) }
                        FilledIconButton(onClick = onPlay, modifier = Modifier.size(76.dp), enabled = track != null,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            PlayPauseGlyph(playing, MaterialTheme.colorScheme.onPrimary, Modifier.size(33.dp))
                        }
                        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, l("Next"), tint = white, modifier = Modifier.size(32.dp)) }
                        IconButton(onClick = onRepeat) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            l("Repeat"), tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent else white, modifier = Modifier.size(23.dp)) }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(panel)) {
                    Box(Modifier.fillMaxWidth().height(18.dp).clickable { showLyrics = true }, contentAlignment = Alignment.Center) {
                        Box(Modifier.width(34.dp).height(4.dp).clip(RoundedCornerShape(50)).background(muted.copy(alpha = .6f)))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l("Lyrics"), color = white, fontFamily = headingFont, fontSize = 20.sp, modifier = Modifier.weight(1f).clickable { showLyrics = true })
                        IconButton(onClick = onEqualizer, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Equalizer, l("Equalizer"), tint = muted, modifier = Modifier.size(19.dp)) }
                        IconButton(onClick = { showLyrics = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.OpenInFull, l("Lyrics"), tint = muted, modifier = Modifier.size(17.dp)) }
                    }
                    LyricsPanel(track, clock, onSeek, Modifier.weight(1f).fillMaxWidth(), preview = true)
                }
            }
        }
    }
    @Composable private fun LyricsPanel(track: Track?, clock: PlaybackClock, onSeek: (Long) -> Unit,
        modifier: Modifier = Modifier, preview: Boolean = false) {
        val timed = remember(track?.lyrics) { parseLrc(track?.lyrics.orEmpty()) }
        val timingPrefs = LocalContext.current.getSharedPreferences("lyrics_timing", MODE_PRIVATE)
        var lead by remember(track?.id) { mutableLongStateOf(timingPrefs.getLong("${track?.id}", 0)) }
        DisposableEffect(timingPrefs, track?.id) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == "${track?.id}") lead = prefs.getLong(key, 0)
            }
            timingPrefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { timingPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        val active by remember(timed, clock, lead) { derivedStateOf { lyricIndex(timed, clock.position, lead) } }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(active, timed, track?.id) {
            if (timed.isNotEmpty() && !listState.isScrollInProgress) {
                val target = active.coerceAtLeast(0)
                if (kotlin.math.abs(target - listState.firstVisibleItemIndex) > 8) listState.scrollToItem(target)
                else listState.animateScrollToItem(target)
            }
        }
        Column(modifier.clip(RoundedCornerShape(26.dp)).background(panel)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = if (preview) 16.dp else 22.dp, vertical = 6.dp)) {
                if (timed.isNotEmpty()) items(timed.size) { index ->
                    val (time, line) = timed[index]
                    Text(line, color = if (index == active) white else muted.copy(alpha = .7f),
                        fontFamily = headingFont, fontSize = if (preview) 20.sp else 24.sp, lineHeight = 31.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onSeek((time - lead).coerceAtLeast(0)) }
                            .padding(vertical = if (preview) 3.dp else 10.dp))
                } else item {
                    Text(track?.lyrics?.ifBlank { null } ?: l("Lyrics are loading or unavailable"),
                        color = if (track?.lyrics.isNullOrBlank()) muted else white,
                        fontFamily = headingFont, fontSize = 19.sp, lineHeight = 29.sp)
                }
            }
        }
    }

    @Composable private fun PageHeader(title: String, onBack: () -> Unit) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, l("Back"), tint = white) }
            Spacer(Modifier.width(8.dp))
            Text(title, color = white, fontSize = 28.sp, fontFamily = headingFont)
        }
    }

    @Composable private fun SettingsPage(onBack: () -> Unit, onDuplicates: () -> Unit,
        onAppearance: () -> Unit,

        onStats: () -> Unit,
        modifier: Modifier = Modifier) {
        val playbackPrefs = remember { getSharedPreferences("playback", MODE_PRIVATE) }
        var crossfade by remember { mutableFloatStateOf(playbackPrefs.getInt("crossfade", 0).toFloat()) }
        var timerMinutes by remember { mutableFloatStateOf(0f) }
        var selectedLanguage by remember { mutableStateOf(AppLanguage.selected(this)) }
        var enhanced by remember { mutableStateOf(getSharedPreferences("eq", MODE_PRIVATE).getBoolean("enhancer", false)) }
        Column(modifier.fillMaxSize()) {
            PageHeader(l("Settings"), onBack)
            LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(panel).clickable(onClick = onAppearance).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, null, tint = accent); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(l("Appearance"), color = white, fontFamily = headingFont, fontSize = 23.sp); Text(l("Customize colors and type"), color = muted, fontSize = 13.sp) }
                        Icon(Icons.Default.ChevronRight, null, tint = white)
                    }
                }
                item { OutlinedButton(onClick = { requestOrScan(true) }, modifier = Modifier.fillMaxWidth()) { Text(l("Rescan library")) } }

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
                    Text(l("Playback"), color = white, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(l(if (enhanced) "Sound enhancement on" else "Sound enhancement off"), color = muted, modifier = Modifier.weight(1f))
                        Switch(enhanced, onCheckedChange = { enhanced = it; AudioEffects.setEnhanced(this@MainActivity, it) })
                    }
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
                    OutlinedButton(onClick = onStats, modifier = Modifier.fillMaxWidth()) {
                        Text(l("Listening statistics"))
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
        activityDestroyed = true
        controller?.release()
        controller = null
        super.onDestroy()
    }
}

internal fun Track.isLossless(): Boolean = mime.contains("flac", true) || mime.contains("wav", true) ||
    mime.contains("alac", true) || mime.contains("aiff", true)

internal fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
