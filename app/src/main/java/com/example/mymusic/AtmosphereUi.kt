package com.example.mymusic

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/** Finite opening sequence, skipped when Android animations are disabled. */
@Composable internal fun LaunchReveal(enabled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val animate = remember { enabled && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    var finished by remember { mutableStateOf(!animate) }
    val reveal = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate) reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        finished = true
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val entry = ((reveal.value - .55f) / .45f).coerceIn(0f, 1f)
            alpha = if (finished) 1f else entry
            scaleX = .96f + .04f * entry; scaleY = scaleX
        }) { content() }
        if (!finished) {
            val tint = accent
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - (reveal.value - .72f) / .28f).coerceIn(0f, 1f) }
                .background(ink).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.width * (.18f + reveal.value * .8f)
                    drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = .24f), Color.Transparent), center, radius), radius)
                    repeat(3) { i ->
                        drawCircle(tint.copy(alpha = .12f * (1f - reveal.value)), radius * (.5f + i * .26f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Vinyl(Modifier.size(144.dp).graphicsLayer { rotationZ = -100f + reveal.value * 280f
                        scaleX = .65f + .35f * reveal.value; scaleY = scaleX }, tint)
                    Spacer(Modifier.height(30.dp))
                    Text("MYMUSIC", color = white, fontSize = 25.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(l("Sound. Stories. You."), color = muted, fontSize = 12.sp, letterSpacing = 2.sp)
                }
            }
        }
    }
}

@Composable internal fun MusicAtmosphere(count: Int, playing: Boolean) {
    val tint = accent
    val entry = remember { Animatable(0f) }
    val phase = rememberPlaybackPhase(playing, 9000)
    val recordLabel = l("Record player")
    LaunchedEffect(Unit) { entry.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .graphicsLayer { alpha = entry.value; translationY = (1f - entry.value) * 16.dp.toPx() }
        .background(Brush.linearGradient(listOf(tint.copy(alpha = .22f), panel, panel)))) {
        Canvas(Modifier.matchParentSize()) {
            val mid = size.height * .76f
            repeat(40) { index ->
                val x = size.width * index / 39
                val h = (8 + 20 * kotlin.math.abs(sin(index * .58f))).dp.toPx()
                drawLine(tint.copy(alpha = .14f), Offset(x, mid - h), Offset(x, mid + h), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l("ON YOUR FREQUENCY"), color = tint, fontSize = 9.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(10.dp))
                Text(l("Your music room"), color = white, fontFamily = headingFont, fontSize = 28.sp, lineHeight = 32.sp)
                Spacer(Modifier.height(12.dp))
                Text(trackCount(count), color = muted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(10.dp))
            Vinyl(Modifier.size(88.dp).semantics { contentDescription = recordLabel }
                .graphicsLayer { rotationZ = phase.value * 360f + (1f - entry.value) * -90f }, tint)
        }
    }
}

@Composable private fun Vinyl(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(Color(0xFF10151E), r)
        repeat(8) { i -> drawCircle(Color.White.copy(alpha = .05f), r * (.43f + .068f * i),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f)) }
        drawArc(Color.White.copy(alpha = .08f), -70f, 38f, false, Offset(r * .07f, r * .07f),
            androidx.compose.ui.geometry.Size(r * 1.86f, r * 1.86f), style = androidx.compose.ui.graphics.drawscope.Stroke(r * .17f))
        drawCircle(tint, r * .33f)
        drawCircle(Color(0xFF10151E), r * .055f)
        repeat(3) { i ->
            val a = (i * .32 + .8) * PI
            val start = Offset(center.x + cos(a).toFloat() * r * .17f, center.y + sin(a).toFloat() * r * .17f)
            drawCircle(Color.Black.copy(alpha = .25f), r * .025f, start)
        }
    }
}
