package com.example.mymusic

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver

/** Keeps the page beneath the expanded player intact and remembers collection origins. */
@Stable
internal class PlayerNavigation(
    initialPage: String = "library",
    initialBase: String = "library",
    initialOrigin: String = "library"
) {
    var page by mutableStateOf(initialPage)
    var basePage by mutableStateOf(initialBase)
        private set
    private var collectionOrigin by mutableStateOf(initialOrigin)
    val contentPage: String get() = if (page == "player") basePage else page

    fun openPlayer() {
        if (page == "player") return
        basePage = page
        page = "player"
    }

    fun openCollection(destination: String) {
        if (page != "album" && page != "artist") collectionOrigin = if (page == "player") "player" else "library"
        page = destination
    }

    fun closePlayer() { page = basePage }
    fun closeCollection() { page = collectionOrigin }

    fun back(equalizerReturnPage: String) {
        page = when (page) {
            "equalizer" -> equalizerReturnPage
            "stats", "appearance" -> "settings"
            "album", "artist" -> collectionOrigin
            "player" -> basePage
            else -> "library"
        }
    }

    companion object {
        val Saver = listSaver<PlayerNavigation, String>(
            save = { listOf(it.page, it.basePage, it.collectionOrigin) },
            restore = { PlayerNavigation(it[0], it[1], it[2]) }
        )
    }
}
