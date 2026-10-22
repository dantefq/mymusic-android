package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ListeningInsightsTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(day: Int, hour: Int = 12) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val tracks = listOf(Track(1,"","First","A","",180000,""), Track(2,"","Second","B","",180000,""),
        Track(3,"","Book","Author","",180000,"",isAudiobook = true))
    @Test fun usesLocalCalendarDaysAndExcludesBooksMissingTracksAndFutureEvents() {
        val plays = listOf(PlayEvent(trackId=1,playedAt=at(15,0)), PlayEvent(trackId=2,playedAt=at(21,9)),
            PlayEvent(trackId=3,playedAt=at(21)), PlayEvent(trackId=99,playedAt=at(21)), PlayEvent(trackId=1,playedAt=at(22)))
        val result = listeningInsights(tracks, plays, 7, at(21,18), zone)
        assertEquals(2,result.starts); assertEquals(2,result.uniqueTracks); assertEquals(2,result.uniqueArtists)
        assertEquals(7,result.days.size); assertEquals(1,result.days.first().starts)
    }
    @Test fun discoveryUsesEarlierHistoryAndCountsDistinctTracks() {
        val plays = listOf(PlayEvent(trackId=1,playedAt=at(10)), PlayEvent(trackId=1,playedAt=at(20)),
            PlayEvent(trackId=2,playedAt=at(20)), PlayEvent(trackId=2,playedAt=at(21)))
        val result = listeningInsights(tracks, plays,7,at(21,18),zone)
        assertEquals(50,result.discoveryPercent); assertEquals(3,result.starts)
        assertEquals(2L,result.topTracks.first().first.id); assertEquals(2,result.topTracks.first().second)
        assertEquals(2,result.longestStreak); assertEquals(2,result.activeDays)
    }
    @Test fun emptyHistoryHasNoInventedFacts() {
        val result = listeningInsights(tracks,emptyList(),30,at(21),zone)
        assertEquals(0,result.starts); assertNull(result.peakHour); assertEquals(0,result.discoveryPercent)
        assertEquals(30,result.days.size); assertTrue(result.topTracks.isEmpty())
    }
    @Test fun collaboratorsAndCaseVariantsAreCountedConsistently() {
        val music = tracks.map { if (it.id == 1L) it.copy(artist = "A; B") else if (it.id == 2L) it.copy(artist = "a") else it }
        val result = listeningInsights(music, listOf(PlayEvent(trackId=1,playedAt=at(20)), PlayEvent(trackId=2,playedAt=at(21))),7,at(21,18),zone)
        assertEquals(2,result.uniqueArtists); assertEquals(2,result.starts)
        assertEquals("A",result.topArtists.first().first); assertEquals(2,result.topArtists.first().second)
    }
    @Test fun peakHourAndStreakReflectActualStarts() {
        val plays = listOf(PlayEvent(trackId=1,playedAt=at(16,22)), PlayEvent(trackId=2,playedAt=at(17,22)),
            PlayEvent(trackId=1,playedAt=at(18,8)), PlayEvent(trackId=1,playedAt=at(20,22)))
        val result = listeningInsights(tracks,plays,7,at(21,18),zone)
        assertEquals(22,result.peakHour); assertEquals(3,result.longestStreak); assertEquals(4,result.activeDays)
    }
}
