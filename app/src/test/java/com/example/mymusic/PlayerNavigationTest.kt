package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class PlayerNavigationTest {
    @Test fun expandedPlayerKeepsItsUnderlyingPageAndIgnoresRepeatedOpen() {
        val navigation = PlayerNavigation("playlists")
        navigation.openPlayer()
        navigation.openPlayer()
        assertEquals("player", navigation.page)
        assertEquals("playlists", navigation.contentPage)
        navigation.closePlayer()
        assertEquals("playlists", navigation.page)
    }

    @Test fun fullPlayerArtistAlbumAndCollaboratorNavigationReturnToPlayer() {
        val navigation = PlayerNavigation()
        navigation.openPlayer()
        navigation.openCollection("artist")
        navigation.openCollection("album")
        navigation.openCollection("artist")
        navigation.back("library")
        assertEquals("player", navigation.page)
        assertEquals("library", navigation.contentPage)
        navigation.back("library")
        assertEquals("library", navigation.page)
    }

    @Test fun albumHeaderBackReturnsToPlayerAndLibraryCollectionsKeepTheirOrigin() {
        val navigation = PlayerNavigation()
        navigation.openPlayer()
        navigation.openCollection("album")
        navigation.closeCollection()
        assertEquals("player", navigation.page)
        navigation.closePlayer()
        navigation.openCollection("artist")
        navigation.closeCollection()
        assertEquals("library", navigation.page)
    }

    @Test fun equalizerReturnsToTheExpandedPlayer() {
        val navigation = PlayerNavigation("audiobooks")
        navigation.openPlayer()
        navigation.page = "equalizer"
        navigation.back("player")
        assertEquals("player", navigation.page)
        navigation.closePlayer()
        assertEquals("audiobooks", navigation.page)
    }
}
