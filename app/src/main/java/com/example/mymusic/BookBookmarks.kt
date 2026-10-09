package com.example.mymusic

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import org.json.JSONObject
import org.json.JSONArray

const val BOOK_KEY = "audiobook_key"
const val BOOK_LAST_CHAPTER = "audiobook_last_chapter"

/** Independent of the current music queue; keyed by the stable library folder. */
class BookBookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("book_bookmarks", Context.MODE_PRIVATE)

    fun all(): Map<String, BookBookmark> = prefs.all.mapNotNull { (key, value) ->
        runCatching {
            val json = JSONObject(value as String)
            key to decode(json)
        }.getOrNull()
    }.toMap()

    fun get(key: String): BookBookmark? = prefs.getString(key, null)?.let { value ->
        runCatching { val json = JSONObject(value)
            decode(json)
        }.getOrNull()
    }

    val changes get() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(all()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(all())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    private fun decode(json: JSONObject): BookBookmark {
        val read = json.optJSONArray("read")
        return BookBookmark(json.getLong("track"), json.getLong("position"), json.getLong("updated"), json.optBoolean("completed"),
            (0 until (read?.length() ?: 0)).mapNotNull { read?.optLong(it)?.takeIf { id -> id > 0 } }.toSet())
    }

    private fun put(key: String, saved: BookBookmark) {
        prefs.edit().putString(key, JSONObject().put("track", saved.trackId).put("position", saved.positionMs)
            .put("updated", saved.updatedAt).put("completed", saved.completed)
            .put("read", JSONArray(saved.completedChapters.toList())).toString()).apply()
    }

    fun markCompleted(item: MediaItem?) {
        val key = item?.mediaMetadata?.extras?.getString(BOOK_KEY) ?: return
        val id = item.mediaId.toLongOrNull() ?: return
        val old = get(key) ?: BookBookmark(id, 0, System.currentTimeMillis())
        if (id !in old.completedChapters) put(key, old.copy(completedChapters = old.completedChapters + id))
    }

    fun setChapterCompleted(book: Audiobook, id: Long, read: Boolean) {
        val old = get(book.key) ?: BookBookmark(book.chapters.first().id, 0, System.currentTimeMillis())
        val previous = if (old.completed && old.completedChapters.isEmpty()) book.chapters.map { it.id }.toSet() else old.completedChapters
        val chapters = if (read) previous + id else previous - id
        put(book.key, old.copy(completedChapters = chapters, completed = book.chapters.all { it.id in chapters }))
    }

    /** Keep checkpoints when previously split chapter groups acquire a single book identity. */
    fun reconcile(books: List<Audiobook>) {
        val saved = all()
        books.forEach { book ->
            val ids = book.chapters.map { it.id }.toSet()
            val matches = saved.filter { (key, value) -> key == book.key || value.trackId in ids }
            val latest = matches.values.maxByOrNull { it.updatedAt } ?: return@forEach
            val read = matches.values.flatMap { it.completedChapters }.filter { it in ids }.toSet()
            val merged = latest.copy(completedChapters = read, completed = latest.completed &&
                (matches.keys == setOf(book.key) || book.chapters.all { it.id in read }))
            if (get(book.key) != merged) put(book.key, merged)
        }
    }

    fun save(item: MediaItem?, position: Long, completed: Boolean = false) {
        val key = item?.mediaMetadata?.extras?.getString(BOOK_KEY) ?: return
        val id = item.mediaId.toLongOrNull() ?: return
        val safePosition = position.coerceAtLeast(0)
        val old = get(key)
        // Queue replacement and stop events must not clear a completed book; seeking back restarts it.
        val finished = completed || (old?.completed == true && old.trackId == id && safePosition >= old.positionMs)
        val read = old?.completedChapters.orEmpty() + if (completed) setOf(id) else emptySet()
        if (old?.trackId == id && old.positionMs == safePosition && old.completed == finished && old.completedChapters == read) return
        put(key, BookBookmark(id, safePosition, System.currentTimeMillis(), finished, read))
    }

    fun save(player: Player) {
        val completed = player.playbackState == Player.STATE_ENDED &&
            player.currentMediaItem?.mediaMetadata?.extras?.getBoolean(BOOK_LAST_CHAPTER) == true
        save(player.currentMediaItem, player.currentPosition, completed)
    }
}
