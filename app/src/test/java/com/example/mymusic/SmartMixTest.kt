package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class SmartMixTest {
    private val now = 1_800_000_000_000L
    private fun library(count: Int, artists: Int = 8) = (1..count).map {
        Track(it.toLong(), "content://$it", "Song $it", "Artist ${it % artists}", "Album ${it % 5}", 180000, "audio/mpeg", genre = "Genre ${it % 4}")
    }
    @Test fun repeatedStartsCoverWholeLibraryBeforeRepeating() {
        val tracks = library(120)
        var state = MixRotation(); val selected = mutableListOf<Long>()
        repeat(5) {
            val batch = selectSmartMix(tracks, emptyList(), rotation = state, seed = it, now = now)
            state = batch.rotation; selected += batch.tracks.map { it.id }
        }
        assertEquals(120, selected.size); assertEquals(120, selected.toSet().size)
        assertEquals(tracks.map { it.id }.toSet(), selected.toSet())
    }
    @Test fun boundaryCrossingDoesNotRepeatInsideBatchOrResetEarly() {
        val tracks = library(53)
        var state = MixRotation(); val selected = mutableListOf<Long>()
        repeat(6) {
            val batch = selectSmartMix(tracks, emptyList(), rotation = state, seed = it, now = now)
            state = batch.rotation
            assertEquals(batch.tracks.size, batch.tracks.distinctBy { it.id }.size)
            selected += batch.tracks.map { it.id }
        }
        assertEquals(53, selected.take(53).toSet().size)
        assertEquals(53, selected.drop(53).take(53).toSet().size)
    }
    @Test fun pendingQueueAndCurrentTrackAreExcludedEvenAtRotationBoundary() {
        val tracks = library(12)
        val blocked = setOf(1L, 2L, 3L)
        val batch = selectSmartMix(tracks, emptyList(), rotation = MixRotation(tracks.map { it.id }.toSet()), blocked = blocked, now = now)
        assertEquals(9, batch.tracks.size); assertTrue(batch.tracks.none { it.id in blocked })
    }
    @Test fun recentPopularTracksDoNotDominateFreshStarts() {
        val tracks = library(80, 1)
        val plays = (1..10).flatMap { id -> (1..100).map { PlayEvent(trackId = id.toLong(), playedAt = now - 1000) } }
        val popularStarts = (0..99).sumOf { seed -> selectSmartMix(tracks, plays, seed = seed, limit = 10, now = now).tracks.count { it.id <= 10 } }
        assertTrue("Recently repeated songs should rarely beat unheard music: $popularStarts", popularStarts < 40)
    }
    @Test fun artistsAreSpacedWhenAlternativesExist() {
        val mix = selectSmartMix(library(96, 12), emptyList(), seed = 7, now = now).tracks
        mix.windowed(3).forEach { assertEquals(3, it.map { track -> track.artist }.toSet().size) }
    }
    @Test fun collaborationsDoNotBypassMainArtistSpacing() {
        val tracks = library(40, 10).map { it.copy(artist = it.artist + " feat. Guest ${it.id}") }
        val mix = selectSmartMix(tracks, emptyList(), seed = 5, now = now).tracks
        mix.windowed(3).forEach { assertEquals(3, it.map { track -> artistNames(track).first() }.toSet().size) }
    }
    @Test fun likesHaveInfluenceButDoNotOverrideRotation() {
        val tracks = library(60, 1); val likes = (1L..10L).toSet()
        val likedStarts = (0..299).count { selectSmartMix(tracks, emptyList(), likes, seed = it, limit = 1, now = now).tracks.first().id in likes }
        assertTrue("Likes should influence selection moderately: $likedStarts", likedStarts in 50..110)
        val first = selectSmartMix(tracks, emptyList(), likes, seed = 1, limit = 30, now = now)
        val second = selectSmartMix(tracks, emptyList(), likes, first.rotation, seed = 2, limit = 30, now = now)
        assertEquals(60, (first.tracks + second.tracks).map { it.id }.toSet().size)
    }
    @Test fun smallLibrariesAndMissingArtistsStayFiniteAndUnique() {
        for (size in 0..4) {
            val tracks = library(size).map { it.copy(artist = "Unknown") }
            val batch = selectSmartMix(tracks, emptyList(), now = now)
            assertEquals(size, batch.tracks.size); assertEquals(size, batch.tracks.distinctBy { it.id }.size)
        }
        assertTrue(selectSmartMix(library(2), emptyList(), blocked = setOf(1, 2), now = now).tracks.isEmpty())
    }
    @Test fun removedTracksArePrunedAndNewTracksEnterBeforeRepeats() {
        val batch = selectSmartMix(library(5), emptyList(), rotation = MixRotation(setOf(1, 2, 3, 99), listOf(99, 1, 2)), limit = 2, now = now)
        assertEquals(setOf(4L, 5L), batch.tracks.map { it.id }.toSet())
        assertFalse(99L in batch.rotation.used); assertFalse(99L in batch.rotation.recent)
    }
    @Test fun audiobooksAndDuplicateIdsCannotEnterMusicMix() {
        val tracks = library(4); val input = tracks + tracks[0] + tracks[1].copy(id = 90, isAudiobook = true)
        assertEquals(4, selectSmartMix(input, emptyList(), now = now).tracks.size)
    }
    @Test fun recentTailPreventsImmediateReplayAtNewCycle() {
        val tracks = library(30, 1)
        val state = MixRotation(tracks.map { it.id }.toSet(), (20L..30L).toList())
        val first = selectSmartMix(tracks, emptyList(), rotation = state, limit = 1, now = now).tracks.first()
        assertFalse(first.id in state.recent)
    }
    @Test fun largeLibraryProducesBoundedBatchAndLedger() {
        val result = selectSmartMix(library(10000, 100), emptyList(), rotation = MixRotation(recent = (1L..1000).toList()), seed = 9, now = now)
        assertEquals(24, result.tracks.size); assertEquals(24, result.tracks.distinctBy { it.id }.size)
        assertTrue(result.rotation.recent.size <= 80)
    }
}
