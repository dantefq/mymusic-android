package com.example.mymusic

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

data class ListeningDay(val epochDay: Long, val starts: Int)
data class ListeningInsights(
    val starts: Int = 0, val uniqueTracks: Int = 0, val uniqueArtists: Int = 0,
    val discoveryPercent: Int = 0, val activeDays: Int = 0, val longestStreak: Int = 0,
    val peakHour: Int? = null, val days: List<ListeningDay> = emptyList(),
    val topTracks: List<Pair<Track, Int>> = emptyList(), val topArtists: List<Pair<String, Int>> = emptyList()
)

/** PlayEvent records starts, not listened duration. Never invent listening hours from song lengths. */
fun listeningInsights(tracks: List<Track>, plays: List<PlayEvent>, days: Int,
    now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ListeningInsights {
    val music = tracks.filterNot { it.isAudiobook }.associateBy { it.id }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val startDay = today.minusDays(days.coerceAtLeast(1).toLong() - 1)
    val start = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
    val history = plays.filter { it.trackId in music && it.playedAt <= now }
    val recent = history.filter { it.playedAt >= start }
    val counts = recent.groupingBy { it.trackId }.eachCount()
    val previousIds = history.filter { it.playedAt < start }.map { it.trackId }.toSet()
    val dayCounts = mutableMapOf<Long, Int>(); val hourCounts = IntArray(24)
    recent.forEach {
        val time = Instant.ofEpochMilli(it.playedAt).atZone(zone)
        val day = time.toLocalDate().toEpochDay()
        dayCounts[day] = (dayCounts[day] ?: 0) + 1; hourCounts[time.hour]++
    }
    var streak = 0; var longest = 0
    val daily = (0 until days.coerceAtLeast(1)).map { offset ->
        val day = startDay.plusDays(offset.toLong()).toEpochDay()
        val count = dayCounts[day] ?: 0
        streak = if (count > 0) streak + 1 else 0; longest = maxOf(longest, streak)
        ListeningDay(day, count)
    }
    val artistCounts = mutableMapOf<String, Int>(); val artistLabels = mutableMapOf<String, String>()
    counts.forEach { (id, count) -> music[id]?.let { track -> artistNames(track).forEach { name ->
        val key = name.lowercase(Locale.ROOT)
        if (key !in setOf("unknown", "<unknown>", "unknown artist", "неизвестный исполнитель")) {
            artistLabels.putIfAbsent(key, name); artistCounts[key] = (artistCounts[key] ?: 0) + count
        }
    } } }
    val artists = artistCounts.map { (key, count) -> artistLabels.getValue(key) to count }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    return ListeningInsights(recent.size, counts.size, artists.size,
        if (counts.isEmpty()) 0 else counts.keys.count { it !in previousIds } * 100 / counts.size,
        dayCounts.size, longest, if (recent.isEmpty()) null else hourCounts.indices.maxBy { hourCounts[it] }, daily,
        counts.mapNotNull { (id, count) -> music[id]?.let { it to count } }
            .sortedWith(compareByDescending<Pair<Track, Int>> { it.second }.thenBy { it.first.title }).take(10), artists.take(5))
}
