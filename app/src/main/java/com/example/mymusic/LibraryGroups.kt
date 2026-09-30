package com.example.mymusic

import java.util.Locale

fun albumKey(track: Track): String = when {
    track.album.isBlank() -> "unknown:${track.id}"
    track.albumId > 0 -> "media:${track.albumId}"
    else -> "tag:${track.album.trim().lowercase(Locale.ROOT)}:${track.albumArtist.ifBlank { track.artist.substringBefore(" feat.").substringBefore(" ft.") }.trim().lowercase(Locale.ROOT)}"
}

fun albumTracks(tracks: List<Track>, key: String): List<Track> = tracks.filter { albumKey(it) == key }
    .sortedWith(compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }.thenBy { it.title.lowercase(Locale.ROOT) })
