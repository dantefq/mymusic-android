package com.example.mymusic

import kotlin.random.Random

fun randomContinuation(tracks: List<Track>, currentId: Long?, random: Random = Random.Default): List<Track> {
    val music = tracks.filterNot { it.isAudiobook }
    return music.filterNot { it.id == currentId }.ifEmpty { music }.shuffled(random).take(20)
}
