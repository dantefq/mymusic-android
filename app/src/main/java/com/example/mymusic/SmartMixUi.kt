package com.example.mymusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

@Composable internal fun SmartMixButton(onClick: () -> Unit, enabled: Boolean, busy: Boolean = false, generation: Int = 0) {
    val shape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .97f else 1f, tween(160), label = "mixPress")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(generation) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1100)) }
    val workingPhase = rememberPlaybackPhase(busy, 1900)
    val tint = androidx.compose.ui.graphics.lerp(accent, Color(0xFFACF2DE), .78f)
    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp).graphicsLayer {
        scaleX = scale.value; scaleY = scale.value; alpha = if (enabled || busy) 1f else .5f
    }.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF163F43), Color(0xFF122C35), panel)))
        .border(1.dp, tint.copy(alpha = .38f), shape)
        .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled && !busy, role = Role.Button, onClick = onClick)) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * .88f, size.height * .38f)
            rotate(reveal.value * 80f + workingPhase.value * 360f, center) {
                repeat(5) { index ->
                    drawArc(tint.copy(alpha = .08f + index * .025f), -100f + index * 27f, 230f, false,
                        topLeft = center - Offset((60 + index * 22).dp.toPx(), (60 + index * 22).dp.toPx()),
                        size = androidx.compose.ui.geometry.Size((120 + index * 44).dp.toPx(), (120 + index * 44).dp.toPx()), style = Stroke(1.2.dp.toPx()))
                }
            }
            repeat(27) { index ->
                val x = size.width * index / 26
                val height = (6 + 13 * kotlin.math.abs(sin(index * .7f + reveal.value * 6f))).dp.toPx()
                drawLine(tint.copy(alpha = .18f), Offset(x, size.height - 12.dp.toPx() - height), Offset(x, size.height - 12.dp.toPx()), 2.dp.toPx())
            }
        }
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shuffle, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(l("LESS REPEATS. MORE DISCOVERIES."), color = tint, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l("Smart Mix"), color = Color.White, fontFamily = headingFont, fontSize = 34.sp, lineHeight = 39.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(l(if (busy) "Finding your next discoveries" else "A fresh route through your music"), color = Color(0xFFB4CAC8), fontSize = 13.sp, lineHeight = 19.sp)
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(64.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(26.dp), color = ink, strokeWidth = 2.dp)
                    else Icon(Icons.Default.PlayArrow, null, tint = ink, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(l("Different artists · Rarely played tracks · Your likes"), color = Color(0xFFB4CAC8), fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(9.dp))
        }
    }
}
