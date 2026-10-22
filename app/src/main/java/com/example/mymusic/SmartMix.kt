package com.example.mymusic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import java.util.Locale

data class MixRotation(val used: Set<Long> = emptySet(), val recent: List<Long> = emptyList())
data class MixSelection(val tracks: List<Track>, val rotation: MixRotation)

/** A persisted, without-replacement rotation. Popularity is a small affinity, never a repeat bonus. */
fun selectSmartMix(
    tracks: List<Track>, plays: List<PlayEvent>, likes: Set<Long> = emptySet(),
    rotation: MixRotation = MixRotation(), blocked: Set<Long> = emptySet(),
    seed: Int = Random.nextInt(), limit: Int = 24, now: Long = System.currentTimeMillis()
): MixSelection {
    val music = tracks.filterNot { it.isAudiobook }.distinctBy { it.id }
    val byId = music.associateBy { it.id }
    val available = music.filterNot { it.id in blocked }
    if (available.isEmpty() || limit <= 0) return MixSelection(emptyList(), rotation)
    val used = rotation.used.filterTo(mutableSetOf()) { it in byId }
    val recent = rotation.recent.filter { it in byId }.takeLast(80).toMutableList()
    val counts = mutableMapOf<Long, Int>()
    val lastPlayed = mutableMapOf<Long, Long>()
    plays.forEach { if (it.trackId in byId && it.playedAt <= now) {
        counts[it.trackId] = (counts[it.trackId] ?: 0) + 1
        lastPlayed[it.trackId] = maxOf(lastPlayed[it.trackId] ?: 0L, it.playedAt)
    } }
    fun artist(track: Track): String = artistNames(track).firstOrNull().orEmpty().trim().lowercase(Locale.ROOT).takeUnless {
        it in setOf("", "unknown", "<unknown>", "unknown artist", "неизвестный исполнитель")
    } ?: "track:${track.id}"
    val artists = music.associate { it.id to artist(it) }
    val weights = available.associate { track ->
        val age = now - (lastPlayed[track.id] ?: Long.MIN_VALUE / 2)
        val cooldown = when { age < 6 * 3600000L -> .08; age < 86400000L -> .25; age < 3 * 86400000L -> .6; else -> 1.0 }
        val discovery = 1.0 + 2.0 / sqrt(1.0 + (counts[track.id] ?: 0))
        track.id to cooldown * discovery * if (track.id in likes) 1.35 else 1.0
    }
    val random = Random(seed)
    val result = mutableListOf<Track>()
    val selectedIds = mutableSetOf<Long>()
    // Each batch is unique even when it crosses a rotation boundary.
    repeat(minOf(limit, available.size)) {
        var pool = available.filterNot { it.id in used || it.id in selectedIds }
        if (pool.isEmpty()) {
            used.clear()
            pool = available.filterNot { it.id in selectedIds }
        }
        val recentArtists = recent.takeLast(2).mapNotNull { artists[it] }.toSet()
        val spaced = pool.filterNot { artists[it.id] in recentArtists }
        pool = spaced.ifEmpty {
            val previous = recent.lastOrNull()?.let { artists[it] }
            pool.filterNot { artists[it.id] == previous }.ifEmpty { pool }
        }
        val recentIds = recent.takeLast(minOf(12, (music.size - 1).coerceAtLeast(0))).toSet()
        val cooled = pool.filterNot { it.id in recentIds }
        pool = cooled.ifEmpty { pool }
        val previousGenre = recent.lastOrNull()?.let { byId[it]?.genre }?.takeIf { it.isNotBlank() }
        val chosen = pool.minBy { track ->
            val genre = if (previousGenre != null && track.genre.equals(previousGenre, true)) .75 else 1.0
            -ln(random.nextDouble().coerceAtLeast(1e-9)) / (weights.getValue(track.id) * genre)
        }
        result += chosen; used += chosen.id; selectedIds += chosen.id; recent += chosen.id
    }
    return MixSelection(result, MixRotation(used, recent.takeLast(80)))
}

/** Shared between activity and service; commits reservations before another request can select. */
class SmartMixStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smart_mix_rotation", Context.MODE_PRIVATE)
    companion object { private val lock = Mutex() }
    suspend fun next(tracks: List<Track>, plays: List<PlayEvent>, likes: Set<Long>, blocked: Set<Long> = emptySet(), limit: Int = 24): List<Track> = withContext(Dispatchers.Default) {
        lock.withLock {
            val rotation = MixRotation(prefs.getStringSet("used", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet(),
                prefs.getString("recent", "").orEmpty().split(',').mapNotNull(String::toLongOrNull))
            val result = selectSmartMix(tracks, plays, likes, rotation, blocked, limit = limit)
            withContext(Dispatchers.IO) {
                check(prefs.edit().putStringSet("used", result.rotation.used.map { it.toString() }.toSet())
                    .putString("recent", result.rotation.recent.joinToString(",")).commit())
            }
            result.tracks
        }
    }
}

/** Compatibility for the old preview surface; production playback uses SmartMixStore. */
fun myWaveMix(tracks: List<Track>, plays: List<PlayEvent>, seed: Int = Random.nextInt()): List<Track> =
    selectSmartMix(tracks, plays, seed = seed, limit = minOf(24, tracks.size)).tracks
