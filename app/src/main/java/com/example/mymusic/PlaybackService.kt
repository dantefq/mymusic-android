package com.example.mymusic

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
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
import androidx.media3.session.DefaultMediaNotificationProvider

class PlaybackService : MediaSessionService() {
    companion object { const val ACTION_ROUTE = "com.example.mymusic.ROUTE" }
    private lateinit var player: ExoPlayer
    private lateinit var overlapPlayer: ExoPlayer
    private var session: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var crossfadeJob: Job? = null
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
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                        events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                        PlayerWidget.update(this@PlaybackService, player.currentMediaItem, player.isPlaying)
                    }
                }
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
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
        session = MediaSession.Builder(this, player).setSessionActivity(activityIntent).build()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.notification_icon)
        setMediaNotificationProvider(notificationProvider)
        scope.launch {
            val prefs = getSharedPreferences("playback", MODE_PRIVATE)
            while (true) {
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
        equalizer?.release(); bassBoost?.release()
        equalizer = runCatching { Equalizer(0, id).apply { enabled = true } }.getOrNull()
        bassBoost = runCatching { BassBoost(0, id).apply { enabled = true } }.getOrNull()
        AudioEffects.equalizer = equalizer
        AudioEffects.bassBoost = bassBoost
        AudioEffects.restore(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        AudioEffects.equalizer = null; AudioEffects.bassBoost = null
        equalizer?.release(); bassBoost?.release()
        crossfadeJob?.cancel()
        session?.release(); player.release(); overlapPlayer.release()
        scope.cancel()
        super.onDestroy()
    }
}
