package com.example.mymusic

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

internal val bookAccent = Color(0xFFE9BE81)

data class SleepTimerState(val deadline: Long = 0, val chapterId: Long = 0, val bookKey: String = "") {
    val active: Boolean get() = deadline > System.currentTimeMillis() || chapterId > 0
}

@Composable internal fun rememberSleepTimer(): SleepTimerState {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("playback", Context.MODE_PRIVATE) }
    fun read() = SleepTimerState(prefs.getLong("sleep_until", 0), prefs.getLong("sleep_chapter", 0), prefs.getString("sleep_book", "").orEmpty())
    var state by remember(prefs) { mutableStateOf(read()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key?.startsWith("sleep_") == true) state = read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SleepTimerSheet(chapterId: Long, bookKey: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("playback", Context.MODE_PRIVATE) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bedtime, null, tint = bookAccent)
                Spacer(Modifier.width(12.dp)); Text(l("Sleep timer"), color = white, fontFamily = FontFamily.Serif, fontSize = 28.sp)
            }
            Text(l("Let the story fade into a dream"), color = muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            listOf(10, 15, 30, 45, 60, 90).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { minutes ->
                        OutlinedButton(onClick = {
                            prefs.edit().putLong("sleep_until", System.currentTimeMillis() + minutes * 60000L)
                                .remove("sleep_chapter").remove("sleep_book").apply()
                            onDismiss()
                        }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, bookAccent.copy(alpha = .25f))) {
                            Text(stringResource(R.string.book_minutes, minutes.toLong()), color = white)
                        }
                    }
                }
            }
            TextButton(onClick = {
                if (chapterId > 0 && bookKey.isNotBlank()) prefs.edit().putLong("sleep_until", 0)
                    .putLong("sleep_chapter", chapterId).putString("sleep_book", bookKey).apply()
                onDismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text(l("At the end of this chapter"), color = bookAccent, modifier = Modifier.weight(1f)); Icon(Icons.Default.AutoStories, null, tint = bookAccent) }
            TextButton(onClick = {
                prefs.edit().putLong("sleep_until", 0).remove("sleep_chapter").remove("sleep_book").apply(); onDismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text(l("Sleep timer off"), color = muted) }
        }
    }
}

@Composable internal fun BookListeningPanel(book: Audiobook, track: Track?, clock: PlaybackClock, bookmarks: BookBookmarks,
    timer: SleepTimerState, onTimer: () -> Unit, onChapters: () -> Unit, modifier: Modifier = Modifier) {
    val saved by remember(bookmarks) { bookmarks.changes }.collectAsStateWithLifecycle(emptyMap())
    val checkpoint = saved[book.key]
    val index = remember(book, track?.id) { book.chapters.indexOfFirst { it.id == track?.id } }
    val futureDuration = remember(book, index) { book.chapters.drop((index + 1).coerceAtLeast(0)).sumOf { it.durationMs.coerceAtLeast(0) } }
    // Minute precision keeps a long audiobook's remaining-time label from invalidating every audio frame.
    val remainingMinutes by remember(book, track?.id, clock) { derivedStateOf {
        val remaining = futureDuration +
            ((track?.durationMs ?: 0) - clock.position).coerceAtLeast(0)
        (remaining + 59999) / 60000
    } }
    var now by remember(timer.deadline) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer.deadline) {
        while (timer.deadline > System.currentTimeMillis()) { delay(30000); now = System.currentTimeMillis() }
    }
    LazyColumn(modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        .background(Brush.verticalGradient(listOf(bookAccent.copy(alpha = .12f), panel))), contentPadding = PaddingValues(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().clickable(onClick = onChapters), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l("In this story"), color = bookAccent, fontFamily = FontFamily.Serif, fontSize = 22.sp)
                    Text(stringResource(R.string.book_chapter_position, (index + 1).coerceAtLeast(1), book.chapters.size), color = muted, fontSize = 12.sp)
                }
                StoryMotion(clock.playing, Modifier.size(42.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("${l("Time remaining")} · ${if (remainingMinutes == 1L) l("Less than a minute") else bookTime(remainingMinutes * 60000)}", color = white, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bookAccent.copy(alpha = .07f))
                .clickable(onClick = onTimer).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bedtime, null, tint = bookAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                val label = when {
                    timer.chapterId > 0 -> l("At the end of this chapter")
                    timer.deadline > now -> stringResource(R.string.book_sleep_in_minutes, ((timer.deadline - now + 59999) / 60000).coerceAtLeast(1))
                    else -> l("Sleep timer")
                }
                Text(label, color = if (timer.active) bookAccent else muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null, tint = bookAccent, modifier = Modifier.size(18.dp))
            }
            if (checkpoint?.completedChapters?.isNotEmpty() == true) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.book_completed_count, checkpoint.completedChapters.count { id -> book.chapters.any { it.id == id } }, book.chapters.size),
                    color = muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable internal fun bookTime(ms: Long): String = when {
    ms >= 3600000 -> stringResource(R.string.book_hours_minutes, ms / 3600000, ms / 60000 % 60)
    ms < 60000 -> formatTime(ms)
    else -> stringResource(R.string.book_minutes, (ms + 59999) / 60000)
}

/** Only visible content enters; no permanent animation loop or offscreen work. */
@Composable internal fun EntryReveal(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val value = remember { Animatable(0f) }
    LaunchedEffect(Unit) { value.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    Box(modifier.graphicsLayer { alpha = value.value; translationY = (1f - value.value) * 14.dp.toPx() }) { content() }
}
