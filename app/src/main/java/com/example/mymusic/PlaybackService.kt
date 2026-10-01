package com.example.mymusic

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.os.SystemClock
import android.content.Intent
import android.app.PendingIntent
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.withContext
import androidx.media3.session.DefaultMediaNotificationProvider

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaSessionService() {
    companion object { const val ACTION_ROUTE = "com.example.mymusic.ROUTE" }
    private lateinit var player: ExoPlayer
    private lateinit var overlapPlayer: ExoPlayer
    private var session: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var crossfadeJob: Job? = null
    private val snapshot by lazy { PlaybackSnapshot(this) }
    private var restoring = true
    private val metadata by lazy { Metadata(this, MusicDb.get(this).tracks()) }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (!restoring) {
                        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) snapshot.saveQueue(player)
                        else if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                            events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                            events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                            events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) snapshot.savePosition(player)
                    }
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                        events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                        PlayerWidget.update(this@PlaybackService, player.currentMediaItem, player.isPlaying)
                    }
                }
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    if (restoring) return
                    val id = mediaItem?.mediaId?.toLongOrNull() ?: return
                    scope.launch(Dispatchers.IO) {
                        MusicDb.get(this@PlaybackService).plays().put(PlayEvent(trackId = id,
                            playedAt = System.currentTimeMillis()))
                        runCatching { metadata.fetchLyrics(id) }
                    }
                }
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != sessionId) {
                        sessionId = audioSessionId
                        attachEffects(audioSessionId)
                    }
                }
            })
        }
        overlapPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            volume = 0f
        }
        val activityIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        snapshot.restore(player)
        restoring = false
        session = MediaSession.Builder(this, player).setSessionActivity(activityIntent).build()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.notification_icon)
        setMediaNotificationProvider(notificationProvider)
        scope.launch {
            val prefs = getSharedPreferences("playback", MODE_PRIVATE)
            while (true) {
                if (player.isPlaying) snapshot.savePosition(player)
                val queueMode = prefs.getString("queue_mode", "off")
                if (queueMode in listOf("random", "smart") && player.mediaItemCount > 0 &&
                    player.mediaItemCount - player.currentMediaItemIndex <= 3) {
                    val currentId = player.currentMediaItem?.mediaId
                    val next = withContext(Dispatchers.IO) {
                        val db = MusicDb.get(this@PlaybackService)
                        val tracks = db.tracks().all().filterNot { it.isAudiobook }
                        if (queueMode == "smart") {
                            val history = db.plays().since(System.currentTimeMillis() - 90L * 86400000)
                            myWaveMix(tracks, history, kotlin.random.Random.nextInt())
                                .filterNot { it.id.toString() == currentId }.ifEmpty { tracks }.take(20)
                        } else randomContinuation(tracks, currentId?.toLongOrNull())
                    }
                    if (prefs.getString("queue_mode", "off") == queueMode && player.currentMediaItem?.mediaId == currentId) {
                        if (player.currentMediaItemIndex > 30) player.removeMediaItems(0, player.currentMediaItemIndex - 10)
                        player.addMediaItems(next.map { track ->
                            MediaItem.Builder().setMediaId(track.id.toString()).setUri(track.uri)
                                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                                    .setAlbumTitle(track.album).setArtworkUri(track.artUri?.let(Uri::parse)).build()).build()
                        })
                    }
                }
                val deadline = prefs.getLong("sleep_until", 0)
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    player.pause()
                    overlapPlayer.pause()
                    prefs.edit().putLong("sleep_until", 0).apply()
                }
                val seconds = prefs.getInt("crossfade", 0)
                if (seconds > 0 && crossfadeJob?.isActive != true && player.isPlaying &&
                    player.hasNextMediaItem() && player.duration > 0 &&
                    player.duration - player.currentPosition in 1..(seconds * 1000L)) {
                    crossfadeJob = scope.launch { crossfade(seconds * 1000L) }
                }
                delay(1000)
            }
        }
    }

    private suspend fun crossfade(duration: Long) {
        val index = player.currentMediaItemIndex
        val next = runCatching { player.getMediaItemAt(index + 1) }.getOrNull() ?: return
        val fadeDuration = (player.duration - player.currentPosition).coerceIn(1L, duration)
        overlapPlayer.setMediaItem(next)
        overlapPlayer.volume = 0f
        overlapPlayer.prepare()
        overlapPlayer.play()
        val start = SystemClock.elapsedRealtime()
        while (player.isPlaying && player.currentMediaItemIndex == index) {
            val ratio = ((SystemClock.elapsedRealtime() - start).toFloat() / fadeDuration).coerceIn(0f, 1f)
            player.volume = 1f - ratio
            overlapPlayer.volume = ratio
            if (ratio >= 1f) break
            delay(40)
        }
        if (player.isPlaying && player.currentMediaItemIndex == index) {
            val handoff = overlapPlayer.currentPosition.coerceAtLeast(0)
            player.volume = 0f
            player.seekToNextMediaItem()
            player.seekTo(handoff)
            var attempts = 0
            while (player.playbackState != Player.STATE_READY && attempts++ < 30) delay(20)
        } else if (player.currentMediaItemIndex == index + 1 && player.isPlaying) {
            player.seekTo(overlapPlayer.currentPosition.coerceAtLeast(0))
        }
        player.volume = 1f
        overlapPlayer.stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ROUTE) {
            val id = intent.getIntExtra("device_id", -1)
            val manager = getSystemService(AUDIO_SERVICE) as AudioManager
            val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
            if (device != null) {
                player.setPreferredAudioDevice(device)
                overlapPlayer.setPreferredAudioDevice(device)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun attachEffects(id: Int) {
        equalizer?.release(); bassBoost?.release(); loudnessEnhancer?.release()
        equalizer = runCatching { Equalizer(0, id).apply { enabled = true } }.getOrNull()
        bassBoost = runCatching { BassBoost(0, id).apply { enabled = true } }.getOrNull()
        loudnessEnhancer = runCatching { LoudnessEnhancer(id).apply { setTargetGain(500) } }.getOrNull()
        AudioEffects.equalizer = equalizer
        AudioEffects.bassBoost = bassBoost
        AudioEffects.loudnessEnhancer = loudnessEnhancer
        AudioEffects.restore(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        snapshot.savePosition(player)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        snapshot.savePosition(player)
        AudioEffects.equalizer = null; AudioEffects.bassBoost = null; AudioEffects.loudnessEnhancer = null
        equalizer?.release(); bassBoost?.release(); loudnessEnhancer?.release()
        crossfadeJob?.cancel()
        session?.release(); player.release(); overlapPlayer.release()
        scope.cancel()
        super.onDestroy()
    }
}
