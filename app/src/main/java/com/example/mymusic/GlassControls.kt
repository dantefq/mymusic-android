package com.example.mymusic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong
import kotlin.math.sin

@Composable internal fun SmartMixButton(onClick: () -> Unit, enabled: Boolean) {
    val shape = RoundedCornerShape(18.dp)
    val color = accent
    val foreground = white
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, tween(140), label = "mixPress")
    Row(Modifier.fillMaxWidth().heightIn(min = 84.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }
        .clip(shape)
        .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(panel, color, .09f), panel)))
        .border(.5.dp, foreground.copy(alpha = .13f), shape)
        .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(),
            enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(48.dp)) {
            // Static record motif: no continuously running animation in the library.
            drawCircle(color.copy(alpha = .12f))
            for (fraction in listOf(.43f, .34f, .25f)) {
                drawCircle(color.copy(alpha = .65f), radius = size.minDimension * fraction, style = Stroke(1.dp.toPx()))
            }
            drawCircle(foreground, radius = 3.dp.toPx())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(l("Smart Mix"), color = foreground, fontFamily = headingFont, fontSize = 23.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(l("Made for you"), color = muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable internal fun MiniProgressRail(clock: PlaybackClock) {
    val color = accent
    val trackColor = white.copy(alpha = .08f)
    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
        drawRect(trackColor)
        val fraction = if (clock.duration > 0) (clock.position.toFloat() / clock.duration).coerceIn(0f, 1f) else 0f
        drawRect(color, size = Size(size.width * fraction, size.height))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GlassSeekBar(clock: PlaybackClock, trackId: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    var scrub by remember(trackId) { mutableStateOf<Float?>(null) }
    val total = clock.duration.coerceAtLeast(1L).toFloat()
    val color = accent
    val foreground = white
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.padding(top = 14.dp)) {
        Box(Modifier.fillMaxWidth().height(46.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(foreground.copy(alpha = .09f), foreground.copy(alpha = .025f))))
            .border(.5.dp, Brush.verticalGradient(listOf(foreground.copy(alpha = .3f), foreground.copy(alpha = .06f))), shape)
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Slider(value = scrub ?: clock.position.toFloat().coerceIn(0f, total), onValueChange = { scrub = it },
                onValueChangeFinished = { scrub?.let { onSeek(it.roundToLong()) }; scrub = null }, valueRange = 0f..total,
                modifier = Modifier.fillMaxWidth(), enabled = enabled,
                thumb = { Canvas(Modifier.size(18.dp)) {
                    drawCircle(color.copy(alpha = .25f))
                    drawCircle(Color.White, radius = size.minDimension * .28f)
                    drawCircle(color, radius = size.minDimension * .16f)
                } },
                track = { state ->
                    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                        val fraction = (state.value / total).coerceIn(0f, 1f)
                        val count = 64
                        val pitch = size.width / count
                        repeat(count) { index ->
                            // Decorative rhythm pattern; no expensive audio decoding while dragging.
                            val amplitude = .22f + .65f * kotlin.math.abs(sin(index * .64f + (trackId % 17)))
                            val barHeight = size.height * amplitude
                            drawRoundRect(if (index.toFloat() / count <= fraction) color else foreground.copy(alpha = .23f),
                                Offset(index * pitch, (size.height - barHeight) / 2), Size(pitch * .42f, barHeight), CornerRadius(pitch))
                        }
                    }
                })
        }
        PlaybackTimeLabels(clock, scrub)
    }
}

@Composable private fun PlaybackTimeLabels(clock: PlaybackClock, scrub: Float?) {
    val seconds by remember(clock) { derivedStateOf { clock.position / 1000 } }
    Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(scrub?.roundToLong() ?: seconds * 1000), color = muted, fontSize = 11.sp)
        Text(formatTime(clock.duration), color = muted, fontSize = 11.sp)
    }
}
