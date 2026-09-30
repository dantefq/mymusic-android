package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class TrackSortingTest {
    @Test fun sortingAndLosslessFilterUseMetadata() {
        val a = Track(1, "file:1", "Zulu", "Beta", "First", 2000, "audio/flac", addedAt = 20)
        val b = Track(2, "file:2", "alpha", "Alpha", "Second", 1000, "audio/mpeg", addedAt = 10)
        assertEquals(listOf(b, a), sortedTracks(listOf(a,b), "Name", false, false))
        assertEquals(listOf(a,b), sortedTracks(listOf(a,b), "Date added", true, false))
        assertEquals(listOf(b,a), sortedTracks(listOf(a,b), "Duration", false, false))
        assertEquals(listOf(a), sortedTracks(listOf(a,b), "Album", false, true))
    }
}
