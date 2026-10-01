package com.example.mymusic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable internal fun albumCount(count: Int) = pluralStringResource(R.plurals.design_albums, count, count)
@Composable internal fun artistCount(count: Int) = pluralStringResource(R.plurals.design_artists, count, count)
@Composable internal fun trackCount(count: Int) = pluralStringResource(R.plurals.design_tracks, count, count)

@Composable internal fun ArtistLinks(track: Track, onArtist: (String) -> Unit, modifier: Modifier = Modifier, size: Int = 13) {
    val names = remember(track.artist, track.title) { artistNames(track) }
    val color = muted
    val credits = remember(names, color) { buildAnnotatedString {
        names.forEachIndexed { index, name ->
            if (index > 0) append(" · ")
            pushStringAnnotation("artist", name)
            withStyle(SpanStyle(color = color)) { append(name) }
            pop()
        }
    } }
    ClickableText(credits, modifier, style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontSize = size.sp, color = color)),
        maxLines = 1, overflow = TextOverflow.Ellipsis, onClick = { offset ->
            credits.getStringAnnotations("artist", offset, offset).firstOrNull()?.let { onArtist(it.item) }
        })
}

@Composable internal fun ConceptDivider(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier, thickness = .5.dp, color = white.copy(alpha = .12f))

@Composable internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = muted, fontSize = 10.sp, letterSpacing = 2.sp)
}

@Composable internal fun ConceptButton(label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    val base = accent
    Row(modifier.height(48.dp).clip(shape).background(
        if (primary) Brush.horizontalGradient(listOf(base.copy(alpha = if (enabled) 1f else .35f),
            androidx.compose.ui.graphics.lerp(base, Color(0xFF004CFF), .25f).copy(alpha = if (enabled) 1f else .35f)))
        else Brush.horizontalGradient(listOf(raised, panel)))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else white
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(10.dp))
            Text(label, color = contentColor, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun SegmentedChoices(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).border(.5.dp, muted.copy(alpha = .4f), RoundedCornerShape(50))) {
        options.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50))
                .background(if (index == selected) accent else Color.Transparent).clickable { onSelect(index) },
                contentAlignment = Alignment.Center) {
                Text(label, color = if (index == selected) MaterialTheme.colorScheme.onPrimary else white, fontSize = 13.sp)
            }
        }
    }
}

@Composable internal fun Artwork(track: Track?, modifier: Modifier, radius: Dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(panel)) {
        // Original geometric fallback keeps missing artwork consistent with the approved visual style.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val cobalt = Color(0xFF2855FF); val cream = Color(0xFFF4E5CA); val coral = Color(0xFFFF987F)
            drawRect(Color(0xFF12243B))
            drawRect(cobalt, size = androidx.compose.ui.geometry.Size(w * .46f, h))
            drawCircle(cream, w * .34f, androidx.compose.ui.geometry.Offset(w * .86f, h * .13f))
            drawCircle(coral, w * .17f, androidx.compose.ui.geometry.Offset(w * .83f, h * .21f))
            drawPath(Path().apply {
                moveTo(0f, 0f); lineTo(w * .72f, h * .55f); lineTo(w * .14f, h); close()
            }, cream)
            drawPath(Path().apply {
                moveTo(w * .43f, h * .15f); lineTo(w, h * .82f); lineTo(w * .43f, h); close()
            }, cobalt)
            drawRoundRect(Color(0xFF081524), androidx.compose.ui.geometry.Offset(w * .64f, h * .51f),
                androidx.compose.ui.geometry.Size(w * .13f, h * .42f), androidx.compose.ui.geometry.CornerRadius(w * .065f))
            repeat(4) { step ->
                drawRect(coral, androidx.compose.ui.geometry.Offset(w * .08f, h * (.73f + step * .05f)),
                    androidx.compose.ui.geometry.Size(w * (.15f + step * .05f), h * .05f))
            }
        }
        if (track?.artUri != null) {
            val context = LocalContext.current
            val request = remember(context, track.artUri) {
                ImageRequest.Builder(context).data(track.artUri).crossfade(280).build()
            }
            AsyncImage(request, contentDescription = l("Album artwork"), contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
    }
}
