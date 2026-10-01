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
import androidx.compose.ui.draw.drawWithCache
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
import kotlin.math.roundToInt
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
    Spacer(Modifier.fillMaxWidth().height(2.dp).drawWithCache {
        val width = size.width.roundToInt()
        val pixels = derivedStateOf(structuralEqualityPolicy()) { progressPixels(clock.position, clock.duration, width) }
        onDrawBehind {
            drawRect(trackColor)
            drawRect(color, size = Size(pixels.value.toFloat(), size.height))
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GlassSeekBar(clock: PlaybackClock, trackId: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    val scrub = remember(trackId) { mutableStateOf<Float?>(null) }
    val total = clock.duration.coerceAtLeast(1L).toFloat()
    val color = accent
    val foreground = white
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.padding(top = 14.dp)) {
        Box(Modifier.fillMaxWidth().height(46.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(foreground.copy(alpha = .09f), foreground.copy(alpha = .025f))))
            .border(.5.dp, Brush.verticalGradient(listOf(foreground.copy(alpha = .3f), foreground.copy(alpha = .06f))), shape)
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            // Cache waveform geometry. Position is read only in drawing; the invisible
            // Material slider retains touch handling, keyboard control and accessibility.
            Spacer(Modifier.fillMaxWidth().height(28.dp).drawWithCache {
                val inset = 9.dp.toPx()
                val width = (size.width - inset * 2).coerceAtLeast(0f)
                val playbackPixels = derivedStateOf(structuralEqualityPolicy()) {
                    progressPixels(clock.position, clock.duration, width.roundToInt())
                }
                val pitch = width / 64
                val heights = FloatArray(64) { index ->
                    size.height * (.22f + .65f * kotlin.math.abs(sin(index * .64f + (trackId % 17))))
                }
                val inactiveColor = foreground.copy(alpha = .23f)
                val barSize = pitch * .42f
                val corner = CornerRadius(pitch)
                onDrawBehind {
                    val fraction = (scrub.value?.div(total)
                        ?: (playbackPixels.value / width.coerceAtLeast(1f))).coerceIn(0f, 1f)
                    val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                    heights.forEachIndexed { index, height ->
                        val x = inset + if (rtl) width - index * pitch - barSize else index * pitch
                        drawRoundRect(if (index.toFloat() / 64 <= fraction) color else inactiveColor,
                            Offset(x, (size.height - height) / 2), Size(barSize, height), corner)
                    }
                    val thumbX = inset + width * if (rtl) 1f - fraction else fraction
                    val center = Offset(thumbX, size.height / 2)
                    drawCircle(color.copy(alpha = .25f), radius = inset, center = center)
                    drawCircle(Color.White, radius = inset * .56f, center = center)
                    drawCircle(color, radius = inset * .32f, center = center)
                }
            })
            SeekInput(clock, scrub, total, enabled, onSeek)
        }
        PlaybackTimeLabels(clock, scrub)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SeekInput(clock: PlaybackClock, scrub: MutableState<Float?>, total: Float,
    enabled: Boolean, onSeek: (Long) -> Unit) {
    val seconds by remember(clock) { derivedStateOf { clock.position / 1000 } }
    Slider(value = scrub.value ?: (seconds * 1000).toFloat().coerceIn(0f, total),
        onValueChange = { scrub.value = it },
        onValueChangeFinished = { scrub.value?.let { onSeek(it.roundToLong()) }; scrub.value = null },
        valueRange = 0f..total, modifier = Modifier.fillMaxWidth(), enabled = enabled && clock.duration > 0,
        thumb = { Spacer(Modifier.size(18.dp)) },
        track = { Spacer(Modifier.fillMaxWidth().height(28.dp)) })
}

@Composable private fun PlaybackTimeLabels(clock: PlaybackClock, scrub: State<Float?>) {
    val seconds by remember(clock) { derivedStateOf { clock.position / 1000 } }
    Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(scrub.value?.roundToLong() ?: seconds * 1000), color = muted, fontSize = 11.sp)
        Text(formatTime(clock.duration), color = muted, fontSize = 11.sp)
    }
}
