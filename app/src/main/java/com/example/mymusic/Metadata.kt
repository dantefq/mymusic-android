package com.example.mymusic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class Metadata(context: Context, private val dao: TrackDao) {
    private val http = OkHttpClient()
    private val credentials = SpotifyCredentials(context)

    suspend fun enrich(track: Track): Track = withContext(Dispatchers.IO) {
        val bearer = runCatching { credentials.bearer() }.getOrNull()
        var title = track.title
        var artist = track.artist
        var art = track.artUri
        var spotifyId = track.spotifyId
        var genre = track.genre
        if (bearer != null) {
            val query = "track:${track.title} artist:${track.artist}"
            val url = "https://api.spotify.com/v1/search?q=${Uri.encode(query)}&type=track&limit=1"
            get(url, bearer)?.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)?.let { item ->
                title = item.optString("name", title)
                val artistObject = item.optJSONArray("artists")?.optJSONObject(0)
                artist = artistObject?.optString("name", artist) ?: artist
                val artistId = artistObject?.optString("id").orEmpty()
                art = item.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                    ?.takeIf { it.isNotBlank() } ?: art
                spotifyId = item.optString("id").takeIf { it.isNotBlank() } ?: spotifyId
                if (artistId.isNotBlank()) {
                    genre = get("https://api.spotify.com/v1/artists/$artistId", bearer)
                        ?.optJSONArray("genres")?.optString(0)?.takeIf { it.isNotBlank() } ?: genre
                }
            }
        }
        val lyricsUrl = "https://lrclib.net/api/get?track_name=${Uri.encode(title)}" +
            "&artist_name=${Uri.encode(artist)}&album_name=${Uri.encode(track.album)}" +
            (if (track.durationMs > 0) "&duration=${track.durationMs / 1000}" else "")
        val lyricsJson = get(lyricsUrl)
        val lyrics = lyricsJson?.optString("syncedLyrics")?.takeIf { it.isNotBlank() }
            ?: lyricsJson?.optString("plainLyrics")?.takeIf { it.isNotBlank() } ?: track.lyrics
        val enriched = track.copy(title = title, artist = artist, artUri = art, lyrics = lyrics, genre = genre,
            spotifyId = spotifyId, fetchedAt = System.currentTimeMillis())
        dao.put(enriched)
        if (genre.isNotBlank()) {
            if (!track.artist.equals("Unknown", true)) dao.fillGenreForArtist(track.artist, genre)
            if (artist != track.artist) dao.fillGenreForArtist(artist, genre)
        }
        enriched
    }

    private fun get(url: String, bearer: String? = null): JSONObject? {
        val builder = Request.Builder().url(url).header("User-Agent", "MyMusic/1.0")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        http.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 && bearer != null) credentials.markDisconnected()
            if (!response.isSuccessful) return null
            return JSONObject(response.body!!.string())
        }
    }
}
