package com.example.mymusic

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Queue is written only when it changes; the small position record is updated separately. */
class PlaybackSnapshot(context: Context) {
    private val prefs = context.getSharedPreferences("last_playback", Context.MODE_PRIVATE)
    // Finite writes can finish after the service stops, preserving the final queue.
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private var queueWrite: Job? = null

    fun saveQueue(player: Player) {
        // Player access stays on its application thread; JSON work runs off the UI thread.
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        queueWrite?.cancel()
        queueWrite = writes.launch {
            queueMutex.withLock {
                val queue = JSONArray()
                items.forEach { item ->
                    currentCoroutineContext().ensureActive()
                    queue.put(JSONObject().put("id", item.mediaId)
                        .put("uri", item.localConfiguration?.uri?.toString().orEmpty())
                        .put("title", item.mediaMetadata.title?.toString().orEmpty())
                        .put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                        .put("album", item.mediaMetadata.albumTitle?.toString().orEmpty())
                        .put("art", item.mediaMetadata.artworkUri?.toString().orEmpty()))
                }
                val serialized = queue.toString()
                currentCoroutineContext().ensureActive()
                prefs.edit().putString("queue", serialized).apply()
            }
        }
        savePosition(player)
    }

    fun savePosition(player: Player) {
        prefs.edit().putInt("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putInt("repeat", player.repeatMode).putBoolean("shuffle", player.shuffleModeEnabled).apply()
    }

    fun restore(player: Player) = runCatching {
        val queue = JSONArray(prefs.getString("queue", "[]"))
        val items = (0 until queue.length()).map { index ->
            val saved = queue.getJSONObject(index)
            MediaItem.Builder().setMediaId(saved.getString("id")).setUri(saved.getString("uri"))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(saved.optString("title"))
                    .setArtist(saved.optString("artist")).setAlbumTitle(saved.optString("album"))
                    .setArtworkUri(saved.optString("art").takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build()).build()
        }
        if (items.isNotEmpty()) {
            player.setMediaItems(items, prefs.getInt("index", 0).coerceIn(items.indices),
                prefs.getLong("position", 0).coerceAtLeast(0))
            player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
            player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
            player.playWhenReady = false
            player.prepare()
        }
    }
}
