package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class LibraryIndexTest {
    private fun song(id: Long, title: String = "Song $id", artist: String = "Echo", albumId: Long = 10) =
        Track(id, "file:$id", title, artist, "Album", 1000, "audio/flac", albumId = albumId)

    @Test fun indexedCollectionsPreserveCreditsAlbumOrderAndAudiobooks() {
        val duet = song(1, artist = "Echo; Mira").copy(trackNumber = 2)
        val solo = song(2).copy(trackNumber = 1)
        val book = song(3, artist = "Narrator", albumId = 11).copy(isAudiobook = true)
        val library = indexLibrary(listOf(duet, solo, book))
        assertEquals(listOf(duet, solo), library.music)
        assertEquals(book, library.byId[3])
        assertEquals(listOf(solo, duet), library.albums[albumKey(duet)])
        assertEquals(listOf(duet, solo), library.artist("ECHO"))
        assertEquals(listOf(duet), library.artist("mira"))
        assertEquals(listOf(book), library.artist("Narrator"))
        assertTrue(library.artist("Missing").isEmpty())
    }

    @Test fun searchAndLosslessFilterKeepArtistAndAlbumResultsConsistent() {
        val tracks = listOf(song(1, "Zulu"), song(2, "alpha", "Echo; Mira"),
            song(3, "beta", "Mira", 11).copy(mime = "audio/mpeg"))
        val browse = browseLibrary(tracks, "mira", "Name", false, true)
        assertEquals(listOf(2L), browse.tracks.map { it.id })
        assertEquals(listOf("Echo", "Mira"), browse.artists.map { it.name })
        assertEquals(1, browse.artistRows.size)
        assertTrue(browse.artists.all { it.albumCount == 1 })
        assertEquals(listOf(tracks[1]), browse.albums.single().second)
    }

    @Test fun rankingAndCollaboratorsUseTheSameArtistTracks() {
        val first = song(1, artist = "Echo feat. Mira")
        val second = song(2)
        val browse = browseArtist(listOf(first, second), "echo", mapOf(2L to 5))
        assertEquals(listOf(second, first), browse.topTracks)
        assertEquals(listOf("Mira"), browse.collaborators.map { it.first })
        assertEquals(listOf(first), browse.collaborators.single().second)
        assertEquals(1, browse.albums.size)
    }
}
