package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QueueLogicTest {
    private fun track(id: Long, book: Boolean = false) = Track(id, "file:$id", "Song $id", "Artist", "Album", 1000, "audio/flac", isAudiobook = book)
    @Test fun continuationExcludesCurrentAndAudiobooks() {
        val next = randomContinuation(listOf(track(1), track(2), track(3, true)), 1, Random(1))
        assertEquals(listOf(2L), next.map { it.id })
    }
    @Test fun oneSongLibraryCanContinue() {
        assertEquals(1L, randomContinuation(listOf(track(1)), 1).single().id)
        assertTrue(randomContinuation(emptyList(), null).isEmpty())
    }
}
