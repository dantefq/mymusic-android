package com.example.mymusic

import java.util.Locale

/** Built on a worker dispatcher once per database emission, never during navigation. */
data class LibraryIndex(
    val tracks: List<Track> = emptyList(),
    val music: List<Track> = emptyList(),
    val byId: Map<Long, Track> = emptyMap(),
    val albums: Map<String, List<Track>> = emptyMap(),
    val artists: Map<String, List<Track>> = emptyMap()
) {
    fun artist(name: String): List<Track> = artists[name.lowercase(Locale.ROOT)].orEmpty()
}

fun indexLibrary(tracks: List<Track>): LibraryIndex = LibraryIndex(
    tracks = tracks,
    music = tracks.filterNot { it.isAudiobook },
    byId = tracks.associateBy { it.id },
    albums = tracks.groupBy(::albumKey).mapValues { (_, songs) ->
        songs.sortedWith(compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
            .thenBy { it.title.lowercase(Locale.ROOT) })
    },
    artists = artistsForLibrary(tracks).mapKeys { it.key.lowercase(Locale.ROOT) }
)

data class LibraryArtist(val name: String, val songs: List<Track>, val albumCount: Int)

data class LibraryBrowse(
    val tracks: List<Track> = emptyList(),
    val artists: List<LibraryArtist> = emptyList(),
    val artistRows: List<List<LibraryArtist>> = emptyList(),
    val albums: List<Pair<String, List<Track>>> = emptyList()
)

fun browseLibrary(tracks: List<Track>, query: String, sort: String, descending: Boolean, lossless: Boolean): LibraryBrowse {
    val matches = if (query.isBlank()) tracks else tracks.filter {
        it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
    }
    val sorted = sortedTracks(matches, sort, descending, lossless)
    val artists = artistsForLibrary(sorted).map { (name, songs) ->
        LibraryArtist(name, songs, songs.map(::albumKey).toSet().size)
    }
    return LibraryBrowse(sorted, artists, artists.chunked(2), sorted.groupBy(::albumKey).map { it.key to it.value })
}

data class ArtistBrowse(
    val albums: List<Pair<String, List<Track>>> = emptyList(),
    val topTracks: List<Track> = emptyList(),
    val collaborators: List<Pair<String, List<Track>>> = emptyList()
)

fun browseArtist(songs: List<Track>, name: String, playCounts: Map<Long, Int>): ArtistBrowse = ArtistBrowse(
    albums = songs.groupBy(::albumKey).map { it.key to it.value },
    topTracks = songs.sortedByDescending { playCounts[it.id] ?: 0 },
    collaborators = artistsForLibrary(songs).filterKeys { !it.equals(name, true) }.map { it.key to it.value }
)
