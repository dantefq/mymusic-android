package com.example.mymusic

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** Only visible transport controls subscribe to the position clock. */
@Composable fun PlaybackProgress(player: Player?, content: @Composable (Long, Long) -> Unit) {
    var position by remember(player) { mutableLongStateOf(0) }
    var duration by remember(player) { mutableLongStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        fun refresh() {
            position = player?.currentPosition?.coerceAtLeast(0) ?: 0
            duration = player?.duration?.coerceAtLeast(0) ?: 0
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
                if (player?.isPlaying == true) position = player.currentPosition.coerceAtLeast(0)
                delay(250)
            }
        }
    }
    content(position, duration)
}
