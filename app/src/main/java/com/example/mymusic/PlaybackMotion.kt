package com.example.mymusic

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.sin

/** A 30 Hz phase clock, suspended on pause, offscreen disposal, and background lifecycle. */
@Composable internal fun rememberPlaybackPhase(playing: Boolean, periodMs: Long): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    LaunchedEffect(playing, lifecycle, periodMs, animationsEnabled) {
        if (!playing || !animationsEnabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = 0L
            while (true) withFrameNanos { frame ->
                if (previous == 0L) previous = frame
                val elapsed = frame - previous
                if (elapsed >= 33_000_000L) {
                    phase.floatValue = (phase.floatValue + elapsed.coerceAtMost(100_000_000L) / (periodMs * 1_000_000f)) % 1f
                    previous = frame
                }
            }
        }
    }
    return phase
}

@Composable internal fun bookPlaybackMotion(playing: Boolean): Modifier {
    val phase = rememberPlaybackPhase(playing, 6000)
    return Modifier.graphicsLayer {
        val wave = sin(phase.value * 2 * PI).toFloat()
        rotationZ = wave * 2.8f
        translationY = wave * 4f * density
    }
}

/** An open book with a page turning from right to left, drawn only while its phase changes. */
@Composable internal fun StoryMotion(playing: Boolean, modifier: Modifier, tint: Color = bookAccent) {
    val phase = rememberPlaybackPhase(playing, 2800)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val mid = w * .5f
        val outline = Stroke(w * .035f)
        val book = Path().apply {
            moveTo(mid, h * .22f); quadraticBezierTo(w * .22f, h * .05f, w * .06f, h * .18f)
            lineTo(w * .06f, h * .82f); quadraticBezierTo(w * .27f, h * .69f, mid, h * .87f)
            quadraticBezierTo(w * .73f, h * .69f, w * .94f, h * .82f)
            lineTo(w * .94f, h * .18f); quadraticBezierTo(w * .78f, h * .05f, mid, h * .22f)
        }
        drawPath(book, tint.copy(alpha = .12f))
        drawPath(book, tint, style = outline)
        drawLine(tint, Offset(mid, h * .22f), Offset(mid, h * .87f), w * .025f)
        repeat(3) { row ->
            val y = h * (.34f + row * .14f)
            drawLine(tint.copy(alpha = .5f), Offset(w * .14f, y), Offset(w * .4f, y + h * .025f), w * .025f)
            drawLine(tint.copy(alpha = .5f), Offset(w * .6f, y + h * .025f), Offset(w * .86f, y), w * .025f)
        }
        val angle = phase.value * PI
        val edge = mid + kotlin.math.cos(angle).toFloat() * w * .42f
        val lift = sin(angle).toFloat() * h * .2f
        val page = Path().apply {
            moveTo(mid, h * .22f)
            quadraticBezierTo((mid + edge) / 2, h * .11f - lift, edge, h * .18f - lift)
            lineTo(edge, h * .8f - lift)
            quadraticBezierTo((mid + edge) / 2, h * .69f - lift, mid, h * .87f)
            close()
        }
        drawPath(page, tint.copy(alpha = .2f))
        drawPath(page, tint, style = outline)
    }
}
