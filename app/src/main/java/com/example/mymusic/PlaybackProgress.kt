package com.example.mymusic

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import kotlin.math.roundToInt

/** Subpixel clock changes do not need another GPU frame for a thin progress rail. */
internal fun progressPixels(position: Long, duration: Long, width: Int): Int {
    if (duration <= 0 || width <= 0) return 0
    return ((position.toDouble() / duration).coerceIn(0.0, 1.0) * width).roundToInt()
}

/** Read position only in the seek rail, time labels, and lyric boundary calculation. */
@Stable
class PlaybackClock {
    var position by mutableLongStateOf(0L)
        internal set
    var duration by mutableLongStateOf(0L)
        internal set
    var playing by mutableStateOf(false)
        internal set
}

@Composable fun rememberPlaybackClock(player: Player?): PlaybackClock {
    val clock = remember(player) { PlaybackClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        fun refresh() {
            clock.position = player?.currentPosition?.coerceAtLeast(0) ?: 0L
            clock.duration = player?.duration?.coerceAtLeast(0) ?: 0L
            clock.playing = player?.isPlaying == true
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = refresh()
        }
        refresh()
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player, lifecycle, clock.playing) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastSample = 0L
            while (player != null && clock.playing) {
                withFrameNanos { frame ->
                    // Lyrics and progress need at most 30 samples/s. Seeks still refresh
                    // immediately through the Player listener, and gestures run at full rate.
                    if (frame - lastSample >= 30_000_000L) {
                        clock.position = player.currentPosition.coerceAtLeast(0)
                        lastSample = frame
                    }
                }
            }
        }
    }
    return clock
}
