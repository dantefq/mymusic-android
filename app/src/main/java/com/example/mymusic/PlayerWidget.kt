package com.example.mymusic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URL

class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        val future = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({
            runCatching {
                val controller = future.get()
                update(context, controller.currentMediaItem, controller.isPlaying)
                controller.release()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE && intent.action != ACTION_NEXT && intent.action != ACTION_PREVIOUS) return
        val result = goAsync()
        val future = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({
            runCatching {
                val player = future.get()
                when (intent.action) {
                    ACTION_TOGGLE -> if (player.isPlaying) player.pause() else player.play()
                    ACTION_NEXT -> player.seekToNextMediaItem()
                    ACTION_PREVIOUS -> player.seekToPreviousMediaItem()
                }
                update(context, player.currentMediaItem, player.isPlaying)
                player.release()
            }
            result.finish()
        }, ContextCompat.getMainExecutor(context))
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.mymusic.WIDGET_TOGGLE"
        const val ACTION_NEXT = "com.example.mymusic.WIDGET_NEXT"
        const val ACTION_PREVIOUS = "com.example.mymusic.WIDGET_PREVIOUS"

        fun update(context: Context, item: MediaItem?, playing: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                val views = RemoteViews(context.packageName, R.layout.widget_player)
                views.setTextViewText(R.id.widget_title, item?.mediaMetadata?.title ?: context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_artist, item?.mediaMetadata?.artist ?: "")
                views.setImageViewResource(R.id.widget_play,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                item?.mediaMetadata?.artworkUri?.let { uri ->
                    runCatching {
                        URL(uri.toString()).openStream().use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()?.let { views.setImageViewBitmap(R.id.widget_art, it) }
                }
                fun action(value: String, request: Int): PendingIntent =
                    PendingIntent.getBroadcast(context, request,
                        Intent(context, PlayerWidget::class.java).setAction(value),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_play, action(ACTION_TOGGLE, 1))
                views.setOnClickPendingIntent(R.id.widget_next, action(ACTION_NEXT, 2))
                views.setOnClickPendingIntent(R.id.widget_previous, action(ACTION_PREVIOUS, 3))
                views.setOnClickPendingIntent(R.id.widget_root,
                    PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }
    }
}
