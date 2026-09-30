package com.example.mymusic

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/** Queue is written only when it changes; the small position record is updated separately. */
class PlaybackSnapshot(context: Context) {
    private val prefs = context.getSharedPreferences("last_playback", Context.MODE_PRIVATE)

    fun saveQueue(player: Player) {
        val queue = JSONArray()
        repeat(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            queue.put(JSONObject().put("id", item.mediaId)
                .put("uri", item.localConfiguration?.uri?.toString().orEmpty())
                .put("title", item.mediaMetadata.title?.toString().orEmpty())
                .put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                .put("album", item.mediaMetadata.albumTitle?.toString().orEmpty())
                .put("art", item.mediaMetadata.artworkUri?.toString().orEmpty()))
        }
        prefs.edit().putString("queue", queue.toString()).apply()
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
