package com.example.mymusic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable fun PlayPauseGlyph(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(if (playing) 1f else 0f, tween(220), label = "transport morph")
    val description = if (playing) l("Pause") else l("Play")
    Canvas(modifier.size(28.dp).semantics { contentDescription = description }) {
        fun polygon(from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>) {
            val path = Path()
            from.zip(to).forEachIndexed { index, (a, b) ->
                val x = (a.first + (b.first - a.first) * fraction) * size.width
                val y = (a.second + (b.second - a.second) * fraction) * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close(); drawPath(path, color)
        }
        polygon(listOf(.25f to .15f, .5f to .30f, .5f to .70f, .25f to .85f),
            listOf(.23f to .15f, .41f to .15f, .41f to .85f, .23f to .85f))
        polygon(listOf(.5f to .30f, .83f to .5f, .83f to .5f, .5f to .70f),
            listOf(.59f to .15f, .77f to .15f, .77f to .85f, .59f to .85f))
    }
}
