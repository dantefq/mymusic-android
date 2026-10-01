package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class DuplicateCleanupTest {
    private fun track(id: Long) = Track(id, "content://media/$id", "Same", "Artist", "Album", 5000, "audio/wav", addedAt = id)
    @Test fun retainsPlayingCopyAndOneCopyPerHash() {
        val plan = duplicateCleanupPlan(listOf(DuplicateGroup("hash", listOf(track(1), track(2), track(3)))), 3, setOf(2))
        assertEquals(setOf(1L, 2L), plan.map { it.remove.id }.toSet())
        assertTrue(plan.all { it.keep.id == 3L })
    }
    @Test fun retainsPlaylistCopyWhenNoneIsPlaying() {
        val plan = duplicateCleanupPlan(listOf(DuplicateGroup("hash", listOf(track(1), track(2)))), null, setOf(2))
        assertEquals(1L, plan.single().remove.id)
        assertEquals(2L, plan.single().keep.id)
        assertTrue(duplicateCleanupPlan(listOf(DuplicateGroup("one", listOf(track(1)))), null, emptySet()).isEmpty())
    }
}
