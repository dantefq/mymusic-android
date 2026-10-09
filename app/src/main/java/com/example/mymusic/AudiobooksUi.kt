package com.example.mymusic

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val bookGold = bookAccent

@Composable internal fun chapterName(track: Track, index: Int): String {
    val number = chapterNumbers(track)
    return "${l("Chapter")} ${number.chapter ?: index + 1}" + (number.part?.let { " · ${l("Part")} $it" } ?: "")
}

@Composable fun AudiobooksScreen(books: List<Audiobook>, details: Map<String, BookDetails>, artwork: Map<String, String>, bookmarks: BookBookmarks,
    selectedKey: String?, onSelect: (String?) -> Unit,
    onPlay: (Audiobook, Track, Long) -> Unit, onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, inputEnabled: Boolean = true, playingId: Long? = null) {
    val saved by remember(bookmarks) { bookmarks.changes }.collectAsStateWithLifecycle(emptyMap())
    val selected = books.firstOrNull { it.key == selectedKey }
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(selected != null && inputEnabled) { onSelect(null) }
    val continueBook: (Audiobook) -> Unit = { book ->
        val resume = bookResume(book, bookmarks.get(book.key))
        onPlay(book, resume.track, resume.positionMs)
    }
    AnimatedContent(selected?.key, modifier.fillMaxSize(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bookshelf") { key ->
        val book = books.firstOrNull { it.key == key }
        if (book != null) {
            val info = details[book.key]
            val bookmark = saved[book.key]
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item("book_header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onSelect(null) }) { Icon(Icons.Default.ArrowBack, l("Back"), tint = bookGold) }
                        Text(l("Your bookshelf"), color = muted, fontSize = 13.sp)
                    }
                    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(bookGold.copy(alpha = .10f), Color.Transparent)))
                        .padding(horizontal = 24.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BookCover(book, info, artwork, Modifier.width(150.dp).height(210.dp), moving = book.chapters.any { it.id == playingId })
                        Spacer(Modifier.height(22.dp))
                        Text(info?.title ?: book.title, color = white, fontFamily = FontFamily.Serif, fontSize = 29.sp,
                            lineHeight = 36.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(info?.author?.ifBlank { book.author } ?: book.author.ifBlank { l("Unknown author") }, color = bookGold, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("${pluralStringResource(R.plurals.book_chapters, book.chapters.size, book.chapters.size)} · ${bookDuration(book.durationMs)}", color = muted, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        RemainingLabel(book, bookmark)
                        Spacer(Modifier.height(20.dp))
                        BookPlayButton(bookmark, { continueBook(book) }, Modifier.fillMaxWidth())
                        if (bookmark != null) {
                            BookmarkCaption(book, bookmark, Modifier.padding(top = 10.dp))
                            TextButton(onClick = { onPlay(book, book.chapters.first(), 0) }) {
                                Icon(Icons.Default.Replay, null, Modifier.size(16.dp), tint = muted)
                                Spacer(Modifier.width(6.dp)); Text(l("Start over"), color = muted)
                            }
                        }
                    }
                    if (!info?.description.isNullOrBlank()) {
                        var expanded by remember(book.key) { mutableStateOf(false) }
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                            Text(l("About the book"), color = bookGold, fontFamily = FontFamily.Serif, fontSize = 20.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(info!!.description, color = muted, fontSize = 14.sp, lineHeight = 22.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { expanded = !expanded }) { Text(l(if (expanded) "Collapse" else "Read more"), color = bookGold) }
                        }
                    }
                    if (info != null) {
                        val context = LocalContext.current
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.sourceUrl))) },
                            modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("${l("Book information")} · ${info.source}", color = muted, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp)); Icon(Icons.Default.OpenInNew, null, Modifier.size(12.dp), tint = muted)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l("Chapters"), color = white, fontFamily = FontFamily.Serif, fontSize = 25.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.PlaylistPlay, null, tint = bookGold)
                    }
                }
                itemsIndexed(book.chapters, key = { _, chapter -> chapter.id }, contentType = { _, _ -> "chapter" }) { index, chapter ->
                    val current = bookmark?.trackId == chapter.id
                    val read = chapterCompleted(book, bookmark, chapter.id)
                    val rowColor by animateColorAsState(if (current) bookGold.copy(alpha = .09f) else Color.Transparent, tween(220), label = "chapter selection")
                    Row(Modifier.fillMaxWidth().background(rowColor)
                        .clickable { onPlay(book, chapter, 0) }.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(index + 1), color = if (current) bookGold else muted.copy(alpha = .6f), fontSize = 12.sp,
                            modifier = Modifier.width(38.dp))
                        Column(Modifier.weight(1f)) {
                            Text(chapterName(chapter, index), color = if (current) bookGold else white, fontSize = 16.sp)
                            Text((if (read) l("Listened") + " · " else "") + formatTime(chapter.durationMs),
                                color = if (read) bookGold.copy(alpha = .7f) else muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        IconButton(onClick = { bookmarks.setChapterCompleted(book, chapter.id, !read) }, modifier = Modifier.size(40.dp)) {
                            AnimatedContent(read, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "read chapter") { done ->
                                Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    l(if (done) "Mark as unlistened" else "Mark as listened"), tint = if (done) bookGold else muted.copy(alpha = .45f), modifier = Modifier.size(20.dp))
                            }
                        }
                        Icon(if (current) Icons.Default.Bookmark else Icons.Default.PlayArrow, l("Play"), tint = bookGold, modifier = Modifier.size(20.dp))
                    }
                    ConceptDivider(Modifier.padding(horizontal = 24.dp))
                }
            }
        } else {
            val visible = books.filter { book ->
                val info = details[book.key]
                query.isBlank() || listOf(book.title, book.author, info?.title.orEmpty(), info?.author.orEmpty()).any { it.contains(query, true) }
            }
            val recent = books.filter { saved[it.key]?.completed == false }.maxByOrNull { saved[it.key]?.updatedAt ?: 0 }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item("shelf_header") {
                    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(bookGold.copy(alpha = .13f), Color.Transparent)))
                        .padding(horizontal = 24.dp, vertical = 24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StoryMotion(playingId != null, Modifier.size(32.dp))
                            Spacer(Modifier.width(10.dp)); Text(l("Stories worth staying for"), color = bookGold, fontSize = 10.sp, letterSpacing = 1.sp,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                if (!refreshing) scope.launch { refreshing = true; try { onRefresh() } finally { refreshing = false } }
                            }, enabled = !refreshing) {
                                if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), color = bookGold, strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, l("Refresh book information"), tint = bookGold)
                            }
                        }
                        Text(l("Your bookshelf"), color = white, fontFamily = FontFamily.Serif, fontSize = 35.sp, lineHeight = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(l("A little quiet. A whole new world."), color = muted, fontSize = 13.sp)
                        Spacer(Modifier.height(18.dp))
                        ShelfIllustration(Modifier.fillMaxWidth().height(48.dp))
                    }
                    if (recent != null && query.isBlank()) {
                        Text(l("Pick up where you left off"), color = bookGold, fontFamily = FontFamily.Serif, fontSize = 21.sp,
                            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
                        BookCard(recent, details[recent.key], artwork, saved[recent.key], { onSelect(recent.key) }, { continueBook(recent) }, featured = true, playingId = playingId)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l("All books"), color = white, fontFamily = FontFamily.Serif, fontSize = 24.sp, modifier = Modifier.weight(1f))
                        Text("${books.size}", color = bookGold, fontSize = 13.sp)
                    }
                    if (books.isNotEmpty()) OutlinedTextField(query, { query = it }, singleLine = true,
                        placeholder = { Text(l("Search books and authors"), fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = bookGold,
                            unfocusedBorderColor = bookGold.copy(alpha = .18f)), modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp))
                }
                items(visible, key = { it.key }, contentType = { "book_folder" }) { entry ->
                    EntryReveal { BookCard(entry, details[entry.key], artwork, saved[entry.key], { onSelect(entry.key) }, { continueBook(entry) }, playingId = playingId) }
                }
                if (books.isEmpty()) item("empty") {
                    Column(Modifier.padding(24.dp)) {
                        Icon(Icons.Default.FolderOpen, null, tint = bookGold, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(l("Every book has its place"), color = white, fontFamily = FontFamily.Serif, fontSize = 23.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(l("Put each book in its own folder inside Audiobooks or Аудиокниги, then rescan the library. Chapters will appear together."),
                            color = muted, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                } else if (visible.isEmpty()) item("no_matches") {
                    Text(l("No matching books"), color = muted, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable private fun BookCard(book: Audiobook, details: BookDetails?, artwork: Map<String, String>, bookmark: BookBookmark?, onOpen: () -> Unit,
    onResume: () -> Unit, featured: Boolean = false, playingId: Long? = null) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, tween(160), label = "book press")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .background(if (featured) bookGold.copy(alpha = .09f) else panel)
        .border(.5.dp, bookGold.copy(alpha = if (featured) .35f else .12f), RoundedCornerShape(20.dp))) {
        Row(Modifier.fillMaxWidth().clickable(interactionSource = interactions, indication = LocalIndication.current, onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, details, artwork, Modifier.width(74.dp).height(104.dp), moving = book.chapters.any { it.id == playingId })
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(details?.title ?: book.title, color = white, fontFamily = FontFamily.Serif, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(details?.author?.ifBlank { book.author } ?: book.author.ifBlank { l("Unknown author") }, color = bookGold, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Text("${pluralStringResource(R.plurals.book_chapters, book.chapters.size, book.chapters.size)} · ${bookDuration(book.durationMs)}", color = muted, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                RemainingLabel(book, bookmark)
            }
            Icon(Icons.Default.ChevronRight, l("Open book"), tint = bookGold, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (bookmark != null) {
                LinearProgressIndicator(progress = { bookProgress(book, bookmark) }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                    color = bookGold, trackColor = bookGold.copy(alpha = .12f))
                BookmarkCaption(book, bookmark, Modifier.padding(vertical = 10.dp))
            }
            BookPlayButton(bookmark, onResume, Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun BookPlayButton(bookmark: BookBookmark?, onPlay: () -> Unit, modifier: Modifier) {
    Button(onClick = onPlay, modifier = modifier.heightIn(min = 46.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bookGold, contentColor = Color(0xFF241B13))) {
        Icon(if (bookmark?.completed == true) Icons.Default.Replay else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(l(if (bookmark == null) "Start listening" else if (bookmark.completed) "Listen again" else "Continue listening"), fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun BookmarkCaption(book: Audiobook, bookmark: BookBookmark, modifier: Modifier = Modifier) {
    val index = book.chapters.indexOfFirst { it.id == bookmark.trackId }
    val label = if (bookmark.completed) l("Finished") else if (index < 0) l("Chapter unavailable. Start from the beginning.")
        else "${chapterName(book.chapters[index], index)} · ${formatTime(bookmark.positionMs)}"
    Text(label, color = muted, fontSize = 11.sp, modifier = modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable private fun bookDuration(ms: Long): String = when {
    ms >= 3600000 -> stringResource(R.string.book_hours_minutes, ms / 3600000, ms / 60000 % 60)
    ms < 60000 -> formatTime(ms)
    else -> stringResource(R.string.book_minutes, ms / 60000)
}

@Composable private fun RemainingLabel(book: Audiobook, bookmark: BookBookmark?) {
    val remaining = bookRemainingMs(book, bookmark)
    Text(if (bookmark?.completed == true) l("Finished") else "${l("Time remaining")} · ${bookTime(remaining)}",
        color = bookGold.copy(alpha = .8f), fontSize = 11.sp)
}

@Composable private fun BookCover(book: Audiobook, details: BookDetails?, artwork: Map<String, String>, modifier: Modifier, moving: Boolean = false) {
    val colors = listOf(Color(0xFF35504E), Color(0xFF603F45), Color(0xFF3D4566), Color(0xFF685238))
    val color = colors[(book.key.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(modifier.then(bookPlaybackMotion(moving)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp, bottomStart = 3.dp))
        .background(Brush.horizontalGradient(listOf(color.copy(alpha = .5f), color, color)))) {
        Column(Modifier.fillMaxSize().padding(start = 13.dp, end = 8.dp, top = 13.dp, bottom = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text((details?.author ?: book.author).uppercase(), color = bookGold.copy(alpha = .8f), fontSize = 7.sp, maxLines = 2)
            Text(details?.title ?: book.title, color = Color(0xFFF4E5CA), fontFamily = FontFamily.Serif, fontSize = 14.sp, lineHeight = 18.sp,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.AutoStories, null, tint = bookGold.copy(alpha = .7f), modifier = Modifier.size(18.dp))
        }
        var failed by remember(details?.coverUri, book.chapters.firstOrNull()?.artUri) { mutableStateOf(false) }
        val art = bookCoverUri(book, details, artwork)
        // Reset a failed local illustration request when its cached file becomes available.
        LaunchedEffect(art) { failed = false }
        if (art != null && !failed) AsyncImage(art, contentDescription = l("Book cover"), contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), onError = { failed = true })
        Box(Modifier.fillMaxHeight().width(7.dp).background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent))))
    }
}

@Composable private fun ShelfIllustration(modifier: Modifier) {
    Canvas(modifier) {
        val palette = listOf(Color(0xFF527570), Color(0xFF8B5960), bookGold, Color(0xFF667395))
        val unit = size.width / 24
        repeat(20) { i ->
            val h = size.height * (.42f + (i * 7 % 11) / 20f)
            val x = i * unit * 1.2f
            drawRoundRect(palette[i % palette.size].copy(alpha = .55f), Offset(x, size.height - h),
                androidx.compose.ui.geometry.Size(unit * .78f, h), androidx.compose.ui.geometry.CornerRadius(2f))
            drawLine(bookGold.copy(alpha = .55f), Offset(x + 2, size.height - h + 7), Offset(x + unit * .78f - 2, size.height - h + 7), 1f)
        }
        drawLine(bookGold.copy(alpha = .4f), Offset(0f, size.height), Offset(size.width, size.height), 2f)
    }
}
