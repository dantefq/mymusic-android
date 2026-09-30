package com.example.mymusic

import java.util.Locale

fun albumKey(track: Track): String = when {
    track.album.isBlank() -> "unknown:${track.id}"
    track.albumId > 0 -> "media:${track.albumId}"
    else -> "tag:${track.album.trim().lowercase(Locale.ROOT)}:${track.albumArtist.ifBlank { track.artist.substringBefore(" feat.").substringBefore(" ft.") }.trim().lowercase(Locale.ROOT)}"
}

fun albumTracks(tracks: List<Track>, key: String): List<Track> = tracks.filter { albumKey(it) == key }
    .sortedWith(compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }.thenBy { it.title.lowercase(Locale.ROOT) })

private val artistSeparator = Regex("(?i)\\s*[;(\\[]?\\s*\\b(?:feat\\.?|ft\\.?|featuring)\\s+|[;\\u0000]|\\s+[&×x/]\\s+")
private val titleCredit = Regex("(?i)[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+([^\\])]+)[)\\]]")

/** Explicit credit separators only: punctuation in names such as AC/DC is preserved. */
fun artistNames(track: Track): List<String> =
    (listOf(track.artist) + titleCredit.findAll(track.title).map { it.groupValues[1] }.toList())
        .flatMap { it.split(artistSeparator) }.map { it.trim(' ', '(', ')', '[', ']') }
        .filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }

fun artistsForLibrary(tracks: List<Track>): Map<String, List<Track>> {
    val names = linkedMapOf<String, String>()
    val groups = linkedMapOf<String, MutableList<Track>>()
    tracks.forEach { track -> artistNames(track).forEach { artist ->
        val key = artist.lowercase(Locale.ROOT)
        names.putIfAbsent(key, artist)
        groups.getOrPut(key) { mutableListOf() }.add(track)
    } }
    return groups.entries.associate { names.getValue(it.key) to it.value.toList() }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
}
