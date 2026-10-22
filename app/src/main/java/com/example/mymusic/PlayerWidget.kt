package com.example.mymusic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.Coil
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { updateAsync(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { updateAsync(context) }

    private fun updateAsync(context: Context) {
        val pending = goAsync()
        commandScope.launch {
            try { withTimeout(6500) { refresh(context).join(); artworkJob?.join(); renderJob?.join() } }
            finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in setOf(ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS)) return
        val pending = goAsync()
        commandScope.launch {
            try {
                withTimeout(7000) {
                    commands.withLock {
                        val future = MediaController.Builder(context.applicationContext,
                            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
                        try {
                            val player = suspendCancellableCoroutine<MediaController> { continuation ->
                                future.addListener({
                                    if (continuation.isActive) runCatching { future.get() }
                                        .onSuccess { continuation.resume(it) }.onFailure { continuation.resumeWithException(it) }
                                }, androidx.core.content.ContextCompat.getMainExecutor(context))
                            }
                            val before = updates.value
                            val current = latest?.takeIf { it.mediaId == player.currentMediaItem?.mediaId }
                            val toggleTarget = if (intent.action == ACTION_TOGGLE && player.mediaItemCount > 0)
                                !(current?.requested ?: widgetRequestsPause(player.playWhenReady, player.playbackState, true)) else null
                            when (intent.action) {
                                ACTION_TOGGLE -> if (toggleTarget == false) player.pause()
                                    else if (player.mediaItemCount > 0) {
                                        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                        player.play()
                                    }
                                ACTION_NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                                ACTION_PREVIOUS -> if (player.mediaItemCount > 0) player.seekToPrevious()
                            }
                            // Wait for the service's real event, not the controller's predicted UI state.
                            withTimeoutOrNull(1800) { updates.first { it > before &&
                                (toggleTarget == null || latest?.requested == toggleTarget) } }
                        } finally {
                            // Media3 drains pending commands before unbinding; also releases late connections on timeout.
                            MediaController.releaseFuture(future)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                refresh(context)
            } catch (_: Exception) { refresh(context) }
            finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.mymusic.WIDGET_TOGGLE"
        const val ACTION_NEXT = "com.example.mymusic.WIDGET_NEXT"
        const val ACTION_PREVIOUS = "com.example.mymusic.WIDGET_PREVIOUS"
        const val OPEN_PLAYER = "widget_open_player"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val commands = Mutex()
        private val rendering = Mutex()
        private val updates = MutableStateFlow(0L)
        private val artwork = LruCache<String, Bitmap>(6)
        @Volatile private var latest: WidgetState? = null
        private var renderJob: Job? = null
        private var artworkJob: Job? = null
        private var artworkKey: String? = null
        private var artworkAttemptAt = 0L

        fun update(context: Context, player: Player) {
            val state = captureWidgetState(player)
            latest = state
            updates.value++
            refresh(context)
        }

        fun stopped(context: Context) {
            latest = (latest ?: WidgetSnapshot(context).read()).copy(requested = false, advancing = false)
            refresh(context)
        }

        @Synchronized fun refresh(context: Context): Job {
            val app = context.applicationContext
            renderJob?.cancel()
            renderJob = scope.launch {
                // Collapse adjacent Media3 state events into one RemoteViews/Binder update.
                delay(32)
                rendering.withLock {
                    val state = latest ?: WidgetSnapshot(app).read()
                    WidgetSnapshot(app).save(state)
                    val manager = AppWidgetManager.getInstance(app)
                    val ids = manager.getAppWidgetIds(ComponentName(app, PlayerWidget::class.java))
                    if (ids.isEmpty()) return@withLock
                    for (id in ids) {
                        ensureActive()
                        val options = manager.getAppWidgetOptions(id)
                        val compact = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 144) < 140
                        manager.updateAppWidget(id, buildViews(app, state, compact, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)))
                    }
                    loadArtwork(app, state.artwork)
                }
            }
            return renderJob!!
        }

        @Synchronized private fun loadArtwork(context: Context, key: String?) {
            if (key == artworkKey && (key == null || artwork.get(key) != null || artworkJob?.isActive == true ||
                    SystemClock.elapsedRealtime() - artworkAttemptAt < 30000)) {
                return
            }
            artworkKey = key
            artworkAttemptAt = SystemClock.elapsedRealtime()
            artworkJob?.cancel()
            if (key == null || artwork.get(key) != null) return
            artworkJob = scope.launch {
                val bitmap = try {
                    withTimeout(4000) {
                        Coil.imageLoader(context).execute(ImageRequest.Builder(context).data(key).size(192, 192).allowHardware(false).build())
                            .drawable?.toBitmap(192, 192)
                    }
                } catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled else null }
                catch (_: Exception) { null }
                ensureActive()
                if (bitmap != null) { artwork.put(key, bitmap); refresh(context) }
            }
        }

        internal fun buildViews(context: Context, state: WidgetState, compact: Boolean, width: Int = 300): RemoteViews {
            val views = RemoteViews(context.packageName, if (compact) R.layout.widget_player_compact else R.layout.widget_player)
            views.setTextViewText(R.id.widget_title, state.title.ifBlank { context.getString(R.string.app_name) })
            views.setTextViewText(R.id.widget_artist, state.artist.ifBlank { context.getString(if (state.hasItem) R.string.widget_local_audio else R.string.widget_choose_music) })
            views.setTextViewText(R.id.widget_status, context.getString(when {
                !state.hasItem -> R.string.widget_open_hint
                state.book -> R.string.widget_audiobook
                else -> R.string.widget_music
            }))
            views.setImageViewResource(R.id.widget_play, if (state.requested) R.drawable.widget_pause else R.drawable.widget_play)
            views.setContentDescription(R.id.widget_play, context.localized(if (state.requested) "Pause" else "Play"))
            views.setImageViewResource(R.id.widget_art, if (state.book) R.drawable.widget_book else R.drawable.widget_record)
            state.artwork?.let { artwork.get(it) }?.let { views.setImageViewBitmap(R.id.widget_art, it) }
            views.setViewVisibility(R.id.widget_art, if (compact && width < 260) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_previous, if (compact && width < 230) View.GONE else View.VISIBLE)
            views.setBoolean(R.id.widget_previous, "setEnabled", state.hasItem && state.previous)
            views.setBoolean(R.id.widget_next, "setEnabled", state.hasItem && state.next)
            views.setFloat(R.id.widget_previous, "setAlpha", if (state.hasItem && state.previous) 1f else .3f)
            views.setFloat(R.id.widget_next, "setAlpha", if (state.hasItem && state.next) 1f else .3f)
            if (!compact) {
                views.setProgressBar(R.id.widget_progress, 1000, state.progress, false)
                val elapsed = if (state.clockRuns && state.capturedAt > 0) (SystemClock.elapsedRealtime() - state.capturedAt).coerceAtLeast(0) else 0
                views.setChronometer(R.id.widget_clock, SystemClock.elapsedRealtime() - state.positionMs - elapsed, null, state.clockRuns)
                views.setTextViewText(R.id.widget_duration, if (state.durationMs > 0) " / ${formatWidgetTime(state.durationMs)}" else "")
            }
            val open = PendingIntent.getActivity(context, 904, Intent(context, MainActivity::class.java).putExtra(OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            fun action(value: String, request: Int) = PendingIntent.getBroadcast(context, request,
                Intent(context, PlayerWidget::class.java).setAction(value), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_art, open)
            views.setOnClickPendingIntent(R.id.widget_play, if (state.hasItem) action(ACTION_TOGGLE, 901) else open)
            views.setOnClickPendingIntent(R.id.widget_next, action(ACTION_NEXT, 902))
            views.setOnClickPendingIntent(R.id.widget_previous, action(ACTION_PREVIOUS, 903))
            return views
        }
    }
}

internal fun formatWidgetTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}
