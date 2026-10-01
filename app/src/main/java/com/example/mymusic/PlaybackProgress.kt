package com.example.mymusic

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** Read position only in the seek rail, time labels, and lyric boundary calculation. */
@Stable
class PlaybackClock {
    var position by mutableLongStateOf(0L)
        internal set
    var duration by mutableLongStateOf(0L)
        internal set
}

@Composable fun rememberPlaybackClock(player: Player?): PlaybackClock {
    val clock = remember(player) { PlaybackClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        fun refresh() {
            clock.position = player?.currentPosition?.coerceAtLeast(0) ?: 0L
            clock.duration = player?.duration?.coerceAtLeast(0) ?: 0L
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = refresh()
        }
        refresh()
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (player?.isPlaying == true) clock.position = player.currentPosition.coerceAtLeast(0)
                delay(if (player?.isPlaying == true) 50 else 250)
            }
        }
    }
    return clock
}
