package com.example.mymusic

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class Metadata(private val context: Context, private val dao: TrackDao) {
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val tags = LocalTagReader(context)

    suspend fun enrichMissing() = withContext(Dispatchers.IO) {
        dao.all().filter { track ->
            track.needsOnline && track.fetchedAt < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        }.forEach { track -> runCatching { enrich(track) } }
    }

    suspend fun enrich(track: Track): Track = withContext(Dispatchers.IO) {
        val uri = Uri.parse(track.uri)
        val path = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val local = tags.read(uri, track.id, path)
        val titleHint = local.title ?: track.title
        val artistHint = local.artist ?: track.artist
        val query = if (artistHint == "Unknown") "recording:\"$titleHint\""
            else "recording:\"$titleHint\" AND artist:\"$artistHint\""
        val searchUrl = "https://musicbrainz.org/ws/2/recording/?query=${Uri.encode(query)}&fmt=json&limit=1"
        val match = musicBrainz(searchUrl)?.optJSONArray("recordings")?.optJSONObject(0)
            ?.takeIf { it.optInt("score") >= 90 }
        val releases = match?.optJSONArray("releases")
        val albumHint = local.album ?: track.album
        val release = (0 until (releases?.length() ?: 0)).mapNotNull { releases?.optJSONObject(it) }
            .firstOrNull { albumHint.isNotBlank() && it.optString("title").equals(albumHint, true) }
            ?: releases?.optJSONObject(0)
        val releaseId = release?.optString("id")?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
        val art = local.artUri ?: track.artUri ?: releaseId?.let { cacheCover(track.id, it) }
        val title = local.title ?: match?.optString("title").tagValue() ?: track.title
        val artist = local.artist ?: match?.optJSONArray("artist-credit")?.optJSONObject(0)
            ?.optString("name").tagValue() ?: track.artist
        val album = local.album ?: release?.optString("title").tagValue() ?: track.album
        val lyricsUrl = "https://lrclib.net/api/get?track_name=${Uri.encode(title)}" +
            "&artist_name=${Uri.encode(artist)}&album_name=${Uri.encode(album)}" +
            (if (track.durationMs > 0) "&duration=${track.durationMs / 1000}" else "")
        val lyricsJson = if (local.lyrics == null && track.lyrics == null) getJson(lyricsUrl) else null
        val lyrics = local.lyrics ?: track.lyrics ?: lyricsJson?.optString("syncedLyrics").tagValue()
            ?: lyricsJson?.optString("plainLyrics").tagValue()
        val recordingId = match?.optString("id")?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
        val genre = local.genre ?: track.genre.ifBlank {
            recordingId?.let { id ->
                musicBrainz("https://musicbrainz.org/ws/2/recording/$id?inc=genres&fmt=json")
                    ?.optJSONArray("genres")?.optJSONObject(0)?.optString("name").tagValue()
            }.orEmpty()
        }
        val enriched = track.copy(title = title, artist = artist, album = album, artUri = art,
            lyrics = lyrics, genre = genre, fetchedAt = System.currentTimeMillis())
        dao.put(enriched)
        enriched
    }

    private suspend fun musicBrainz(url: String): JSONObject? = rateLimit.withLock {
        val wait = 1_100L - (System.currentTimeMillis() - lastRequest)
        if (wait > 0) delay(wait)
        lastRequest = System.currentTimeMillis()
        getJson(url)
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string()?.let(::JSONObject) else null
        }
    }.getOrNull()

    private fun cacheCover(id: Long, releaseId: String): String? = runCatching {
        val target = File(File(context.filesDir, "covers").apply { mkdirs() }, "$id.online.jpg")
        if (!target.exists()) {
            val request = Request.Builder()
                .url("https://coverartarchive.org/release/$releaseId/front-250")
                .header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = ByteArrayOutputStream()
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    while (bytes.size() <= 2_000_000) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes.write(buffer, 0, read)
                    }
                }
                if (bytes.size() > 2_000_000 || bytes.size() == 0) return@runCatching null
                target.writeBytes(bytes.toByteArray())
            }
        }
        Uri.fromFile(target).toString()
    }.getOrNull()

    companion object {
        private const val USER_AGENT = "MyMusic/1.0 (https://github.com/dantefq/mymusic-android)"
        private val rateLimit = Mutex()
        private var lastRequest = 0L
    }
}
