package com.example.mymusic

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

data class WidgetState(
    val mediaId: String = "", val title: String = "", val artist: String = "", val artwork: String? = null,
    val requested: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0,
    val previous: Boolean = false, val next: Boolean = false, val book: Boolean = false,
    val speed: Float = 1f, val capturedAt: Long = 0, val advancing: Boolean = false
) {
    val progress: Int get() = if (durationMs <= 0) 0 else (positionMs.coerceIn(0, durationMs).toDouble() / durationMs * 1000).toInt()
    val hasItem: Boolean get() = mediaId.isNotBlank()
    val clockRuns: Boolean get() = requested && advancing && speed == 1f && hasItem
}

internal fun widgetRequestsPause(playWhenReady: Boolean, playbackState: Int, hasItem: Boolean): Boolean =
    hasItem && playWhenReady && playbackState != Player.STATE_ENDED

internal fun captureWidgetState(player: Player): WidgetState {
    val item = player.currentMediaItem ?: return WidgetState()
    return WidgetState(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty(),
        item.mediaMetadata.artworkUri?.toString(), widgetRequestsPause(player.playWhenReady, player.playbackState, true),
        player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
        player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS), player.hasNextMediaItem() && player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
        item.mediaMetadata.extras?.getString(BOOK_KEY) != null, player.playbackParameters.speed, SystemClock.elapsedRealtime(), player.isPlaying)
}

/** Only small display metadata is persisted; passive widget updates never start the playback service. */
internal class WidgetSnapshot(context: Context) {
    private val prefs = context.getSharedPreferences("widget_display", Context.MODE_PRIVATE)
    private val playback = context.getSharedPreferences("last_playback", Context.MODE_PRIVATE)
    fun save(state: WidgetState) {
        val serialized = JSONObject().put("id", state.mediaId).put("title", state.title).put("artist", state.artist)
            .put("art", state.artwork.orEmpty()).put("position", state.positionMs).put("duration", state.durationMs)
            .put("previous", state.previous).put("next", state.next).put("book", state.book).toString()
        if (prefs.getString("state", null) != serialized) prefs.edit().putString("state", serialized).apply()
    }
    // A new process always displays pause until the live service publishes its real state.
    fun read(): WidgetState = runCatching {
        val saved = prefs.getString("state", null)
        if (saved != null) {
            val json = JSONObject(saved)
            WidgetState(json.optString("id"), json.optString("title"), json.optString("artist"), json.optString("art").takeIf { it.isNotBlank() },
                positionMs = json.optLong("position"), durationMs = json.optLong("duration"), previous = json.optBoolean("previous"),
                next = json.optBoolean("next"), book = json.optBoolean("book"))
        } else {
            val queue = JSONArray(playback.getString("queue", "[]"))
            if (queue.length() == 0) WidgetState() else {
                val index = playback.getInt("index", 0).coerceIn(0, queue.length() - 1)
                val item = queue.getJSONObject(index)
                WidgetState(item.optString("id"), item.optString("title"), item.optString("artist"), item.optString("art").takeIf { it.isNotBlank() },
                    positionMs = playback.getLong("position", 0), previous = true, next = index < queue.length() - 1, book = item.optString("book").isNotBlank())
            }
        }
    }.getOrDefault(WidgetState())
}
