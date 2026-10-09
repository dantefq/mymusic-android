package com.example.mymusic

import java.util.Locale

data class Audiobook(val key: String, val title: String, val author: String, val chapters: List<Track>) {
    val durationMs: Long get() = chapters.sumOf { it.durationMs.coerceAtLeast(0) }
}

private val bookRoots = setOf("audiobooks", "audiobook", "audio books", "books", "аудиокниги", "аудиокнига", "книги")
private val partFolder = Regex("(?iu)^(?:(?:cd|disc|disk|part|chapter|volume|том|диск|часть|глава)[ ._-]*\\d+|chapters|главы)$")
private val chapterWord = Regex("(?iu)(?:chapter|chap|glava|glav|глава|гл)[ ._-]*(\\d+)")
private val partWord = Regex("(?iu)(?:part|pt|chast|часть|ч)[ ._-]*(\\d+)")
internal fun cleanBookText(value: String): String = value.replace(Regex("(?i)\\.(mp3|m4b|m4a|aac|ogg|opus|flac|wav|wma)$"), "")
    .replace('_', ' ').replace(Regex("\\s+"), " ").trim(' ', '-', '.')

fun isAudiobookPath(path: String): Boolean = path.replace('\\', '/').split('/').any {
    val name = it.lowercase(Locale.ROOT)
    name in bookRoots || name.contains("audiobook") || name.contains("аудиокниг")
}

private fun bookFolder(track: Track): String {
    val segments = track.folderPath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }.toMutableList()
    while (segments.isNotEmpty() && partFolder.matches(segments.last())) segments.removeAt(segments.lastIndex)
    if (segments.lastOrNull()?.lowercase(Locale.ROOT) in bookRoots + setOf("music", "download", "downloads", "музыка")) return ""
    return segments.joinToString("/")
}

private fun usefulTitle(value: String): String? = cleanBookText(value).takeIf {
    it.any(Char::isLetter) && it.lowercase(Locale.ROOT) !in bookRoots + setOf("unknown", "unknown album", "неизвестный альбом") &&
        !Regex("(?iu)^(?:(?:chapter|chap|glava|glav|part|chast|глава|часть)[ ._-]*\\d+[a-zа-я]?[ ._-]*)+$").matches(it)
}

private val unknownAuthors = setOf("unknown", "<unknown>", "неизвестный автор", "неизвестный исполнитель", "unknown artist", "various artists")
internal fun bookAuthorValue(value: String): String? = value.trim().takeIf {
    it.any(Char::isLetter) && it.lowercase(Locale.ROOT) !in unknownAuthors && usefulTitle(it) != null
}

/** Recover author/title from 'Author - Book' and Audiobooks/Author/Book when tags are absent. */
internal fun bookNameHints(track: Track): Pair<String, String> {
    val folder = bookFolder(track)
    val folderName = folder.substringAfterLast('/')
    val name = usefulTitle(folderName) ?: folderName.takeIf { it.matches(Regex("\\d{3,}")) } ?: fileBookTitle(track)
    // A filename such as '01 - lun - glava001m' describes a chapter, not an author/title pair.
    // A named book folder is more reliable than any filename prefix.
    val candidates = listOf(name, usefulTitle(track.album).orEmpty()) + if (folder.isBlank()) listOf(fileBookTitle(track)) else emptyList()
    for (candidate in candidates) {
        val split = candidate.split(Regex("\\s+[—–-]\\s+"), limit = 2)
        if (split.size == 2 && bookAuthorValue(split[0]) != null && usefulTitle(split[1]) != null) {
            return split[1] to split[0]
        }
    }
    val parts = folder.split('/')
    val root = parts.indexOfLast { it.lowercase(Locale.ROOT) in bookRoots }
    val author = if (root >= 0 && parts.size >= root + 3) bookAuthorValue(parts[parts.lastIndex - 1]).orEmpty() else ""
    return name to author
}

private fun fileBookTitle(track: Track): String = usefulTitle(cleanBookText(track.fileName.ifBlank { track.title })
    .replace(Regex("(?iu)[ ._-]*(?:chapter|chap|glava|glav|part|chast|глава|часть)[ ._-]*\\d+.*$"), "")
    .replace(Regex("^[\\d ._-]+|[ ._-]+\\d+(?:[ ._-]+\\d+)*$"), "")) ?: cleanBookText(track.title)

fun audiobookKey(track: Track): String {
    val folder = bookFolder(track)
    if (folder.isNotBlank()) return "folder:$folder" // Preserve case: Android volumes can be case-sensitive.
    val title = usefulTitle(track.album) ?: fileBookTitle(track)
    return "book:${title.lowercase(Locale.ROOT)}:${track.albumArtist.ifBlank { track.artist }.lowercase(Locale.ROOT)}"
}

data class ChapterNumbers(val chapter: Int?, val part: Int?)

fun chapterNumbers(track: Track): ChapterNumbers {
    val name = cleanBookText(track.fileName.ifBlank { track.title })
    val explicit = chapterWord.find(name)?.groupValues?.get(1)?.toIntOrNull()
    val part = partWord.find(name)?.groupValues?.get(1)?.toIntOrNull()
    // A leading 01_02 means chapter 1, part 2. Other digits in a literary title are not chapter numbers.
    val leading = Regex("^(\\d+)(?:[ ._-]+(\\d+))?(?:[ ._-]|$)").find(name)
    val trailing = Regex("[ ._-]+(\\d+)(?:[ ._-]+(\\d+))?$").find(name)
    val numeric = leading ?: trailing
    val tagNumber = (track.trackNumber % 1000).takeIf { it > 0 }
    val folder = track.folderPath.replace('\\', '/').trim('/').substringAfterLast('/')
    val folderPart = Regex("(?iu)^(?:cd|disc|disk|part|volume|том|диск|часть)[ ._-]*(\\d+)$")
        .find(folder)?.groupValues?.get(1)?.toIntOrNull()
    return ChapterNumbers(explicit ?: numeric?.groupValues?.get(1)?.toIntOrNull() ?: tagNumber,
        part ?: numeric?.groupValues?.get(2)?.toIntOrNull() ?: folderPart)
}

/** Compare digit runs numerically, so 2 sorts before 10 even without zero padding. */
internal fun naturalCompare(a: String, b: String): Int {
    val left = Regex("\\d+|\\D+").findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
    val right = Regex("\\d+|\\D+").findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]; val y = right[i]
        val cmp = if (x.first().isDigit() && y.first().isDigit()) {
            val xn = x.trimStart('0').ifEmpty { "0" }; val yn = y.trimStart('0').ifEmpty { "0" }
            xn.length.compareTo(yn.length).takeIf { it != 0 } ?: xn.compareTo(yn)
        } else x.compareTo(y)
        if (cmp != 0) return cmp
    }
    return left.size.compareTo(right.size)
}

fun groupAudiobooks(tracks: List<Track>): List<Audiobook> = tracks.filter { it.isAudiobook }.groupBy(::audiobookKey).map { (key, items) ->
    val album = items.mapNotNull { usefulTitle(it.album) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val folder = bookFolder(items.first()).substringAfterLast('/')
    val hints = bookNameHints(items.first())
    val title = if (hints.second.isNotBlank()) hints.first else album ?: usefulTitle(folder)
        ?: folder.takeIf { it.matches(Regex("\\d{3,}")) } ?: fileBookTitle(items.first())
    val author = hints.second.ifBlank { items.mapNotNull { bookAuthorValue(it.albumArtist) ?: bookAuthorValue(it.artist) }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
    }
    val ordered = items.sortedWith { a, b ->
        val folderOrder = naturalCompare(a.folderPath, b.folderPath)
        if (folderOrder != 0) folderOrder else {
            val x = chapterNumbers(a); val y = chapterNumbers(b)
            compareValues(x.chapter ?: Int.MAX_VALUE, y.chapter ?: Int.MAX_VALUE).takeIf { it != 0 }
                ?: compareValues(x.part ?: 0, y.part ?: 0).takeIf { it != 0 }
                ?: naturalCompare(a.fileName.ifBlank { a.title }, b.fileName.ifBlank { b.title }).takeIf { it != 0 }
                ?: a.id.compareTo(b.id)
        }
    }
    Audiobook(key, title, author, ordered)
}.sortedBy { it.title.lowercase(Locale.ROOT) }

data class BookBookmark(val trackId: Long, val positionMs: Long, val updatedAt: Long, val completed: Boolean = false,
    val completedChapters: Set<Long> = emptySet())

data class BookResume(val track: Track, val positionMs: Long)

fun bookResume(book: Audiobook, bookmark: BookBookmark?): BookResume {
    val track = bookmark?.takeUnless { it.completed }?.let { saved -> book.chapters.firstOrNull { it.id == saved.trackId } }
    return if (track == null) BookResume(book.chapters.first(), 0) else BookResume(track,
        (bookmark?.positionMs ?: 0).coerceAtLeast(0).let { if (track.durationMs > 0) it.coerceAtMost(track.durationMs - 1) else it })
}

fun bookProgress(book: Audiobook, bookmark: BookBookmark?): Float {
    if (bookmark == null || book.durationMs <= 0) return 0f
    if (bookmark.completed) return 1f
    val index = book.chapters.indexOfFirst { it.id == bookmark.trackId }
    if (index < 0) return 0f
    return ((book.chapters.take(index).sumOf { it.durationMs } + bookmark.positionMs.coerceIn(0, book.chapters[index].durationMs.coerceAtLeast(0)))
        .toFloat() / book.durationMs).coerceIn(0f, 1f)
}

fun bookRemainingMs(book: Audiobook, bookmark: BookBookmark?): Long {
    if (bookmark == null) return book.durationMs
    if (bookmark.completed) return 0
    val index = book.chapters.indexOfFirst { it.id == bookmark.trackId }
    if (index < 0) return book.durationMs
    return (book.chapters.drop(index).sumOf { it.durationMs.coerceAtLeast(0) } -
        bookmark.positionMs.coerceIn(0, book.chapters[index].durationMs.coerceAtLeast(0))).coerceAtLeast(0)
}

fun chapterCompleted(book: Audiobook, bookmark: BookBookmark?, id: Long): Boolean =
    book.chapters.any { it.id == id } && ((bookmark?.completed == true && bookmark.completedChapters.isEmpty()) || id in bookmark?.completedChapters.orEmpty())

/** Conservative matching: unrelated search results must never rename a local book. */
fun matchesBook(title: String, author: String, candidateTitle: String, candidateAuthor: String): Boolean {
    fun tokens(text: String) = text.lowercase(Locale.ROOT).replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 1 }.toSet()
    val expected = tokens(title); val actual = tokens(candidateTitle)
    if (expected.isEmpty() || actual.isEmpty()) return false
    if (expected.intersect(actual).size.toDouble() / maxOf(expected.size, actual.size) < .75) return false
    val authors = tokens(author)
    return authors.isEmpty() || author.equals("Unknown", true) || authors.intersect(tokens(candidateAuthor)).isNotEmpty()
}
