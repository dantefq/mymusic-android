package com.example.mymusic

import org.junit.Assert.assertEquals
import org.junit.Test

class ForYouMixTest {
    @Test fun includesEveryTrackWithoutDuplicates() {
        val old = System.currentTimeMillis() - 60L * 86400000
        val fresh = System.currentTimeMillis()
        val tracks = listOf(
            Track(1, "content://1", "Frequent", "A", "", 1000, "audio/mpeg", genre = "rock", addedAt = old),
            Track(2, "content://2", "Recent", "B", "", 1000, "audio/mpeg", addedAt = fresh),
            Track(3, "content://3", "Older", "C", "", 1000, "audio/mpeg", addedAt = old)
        )
        val plays = (0 until 3).map { PlayEvent(trackId = 1, playedAt = fresh) }
        assertEquals(setOf(1L, 2L, 3L), myWaveMix(tracks, plays).map { it.id }.toSet())
        assertEquals(3, myWaveMix(tracks, plays).size)
    }
}
