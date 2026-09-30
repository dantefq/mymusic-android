package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class LibraryGroupsTest {
    private fun song(id: Long, artist: String, albumId: Long = 1, number: Int = 0) =
        Track(id, "file:$id", "Song $id", artist, "Album", 1000, "audio/flac", albumId = albumId, trackNumber = number)
    @Test fun collaboratorsAppearUnderEachArtist() {
        val track = song(1, "Echo Division (feat. Mira Sol)")
        assertEquals(listOf("Echo Division", "Mira Sol"), artistNames(track))
        assertEquals(setOf("Echo Division", "Mira Sol"), artistsForLibrary(listOf(track)).keys)
        artistsForLibrary(listOf(track)).values.forEach { assertEquals(listOf(track), it) }
    }
    @Test fun titleCreditsAndExplicitSeparatorsAreSupported() {
        assertEquals(listOf("Echo", "Mira", "Lume"), artistNames(song(1, "Echo; Mira").copy(title = "Light (ft. Lume)")))
        assertEquals(listOf("Echo", "Mira"), artistNames(song(1, "Echo & Mira")))
        assertEquals(listOf("AC/DC"), artistNames(song(1, "AC/DC")))
    }
    @Test fun albumIdGroupsCollaborationsAndPreservesOrder() {
        val tracks = listOf(song(1, "Echo", 10, 2), song(2, "Echo feat. Mira", 10, 1), song(3, "Other", 11, 1))
        assertEquals(listOf(2L, 1L), albumTracks(tracks, albumKey(tracks.first())).map { it.id })
    }
}
