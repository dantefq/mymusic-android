package com.example.mymusic

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class BookDetails(val title: String, val author: String, val description: String,
    val coverUri: String?, val source: String, val sourceUrl: String, val fetchedAt: Long)

fun matchingBookDetails(book: Audiobook, details: BookDetails?): BookDetails? = details?.takeIf {
    matchesBook(book.title, book.author, it.title, it.author)
}

/** One request per book, cached independently from song metadata and chapter filenames. */
class BookMetadata(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("book_metadata", Context.MODE_PRIVATE)
    private val mutableDetails = MutableStateFlow(readCache())
    val details = mutableDetails.asStateFlow()
    private val illustrations = BookArtwork(context)
    private val mutableArtwork = MutableStateFlow<Map<String, String>>(emptyMap())
    val artwork = mutableArtwork.asStateFlow()
    private val fetchLock = Mutex()
    private var requestFailed = false

    private fun readCache(): Map<String, BookDetails> = prefs.all.mapNotNull { (key, value) ->
        runCatching {
            val json = JSONObject(value as String)
            if (json.optString("title").isBlank()) null else key to BookDetails(json.getString("title"),
                json.optString("author"), json.optString("description"), json.optString("cover").takeIf { it.isNotBlank() },
                json.optString("source"), json.optString("url"), json.getLong("fetched"))
        }.getOrNull()
    }.toMap()

    suspend fun enrich(books: List<Audiobook>, force: Boolean = false) = withContext(Dispatchers.IO) {
        fetchLock.withLock {
            val currentBooks = books.associateBy { it.key }
            mutableDetails.value = mutableDetails.value.filter { (key, info) ->
                currentBooks[key]?.let { matchingBookDetails(it, info) != null } ?: true
            }
            val prepared = books.associate { book ->
                val info = mutableDetails.value[book.key]
                val display = book.copy(title = info?.title ?: book.title, author = info?.author?.ifBlank { book.author } ?: book.author)
                book.key to illustrations.ensure(display)
            }
            mutableArtwork.value = prepared
            for (book in books) {
                val cached = runCatching { JSONObject(prefs.getString(book.key, "{}").orEmpty()) }.getOrNull()
                val maxAge = if (cached?.optInt("version") != 3) 0 else TimeUnit.DAYS.toMillis(if (cached.optString("title").isNullOrBlank()) 1 else
                    if (cached?.optString("author").isNullOrBlank() || cached?.optString("cover").isNullOrBlank()) 1 else 30)
                if (!force && System.currentTimeMillis() - (cached?.optLong("fetched") ?: 0) < maxAge) continue
                requestFailed = false
                val primary = openLibrary(book)
                val fallback = if (primary == null || primary.author.isBlank() || primary.coverUri == null) googleBooks(book) else null
                val result = when {
                    primary == null -> fallback
                    fallback == null -> primary
                    else -> primary.copy(author = primary.author.ifBlank { fallback.author },
                        coverUri = primary.coverUri ?: fallback.coverUri, description = primary.description.ifBlank { fallback.description },
                        source = "Open Library / Google Books")
                }
                // Do not replace successful cached metadata when a provider is temporarily unavailable.
                if (result != null) {
                    val json = JSONObject().put("version", 3).put("title", result.title).put("author", result.author)
                        .put("description", result.description).put("cover", result.coverUri.orEmpty())
                        .put("source", result.source).put("url", result.sourceUrl).put("fetched", result.fetchedAt)
                    prefs.edit().putString(book.key, json.toString()).apply()
                    mutableDetails.value = mutableDetails.value + (book.key to result)
                    if (result.coverUri == null) mutableArtwork.value = mutableArtwork.value +
                        (book.key to illustrations.ensure(book.copy(title = result.title, author = result.author.ifBlank { book.author })))
                } else if (!requestFailed && cached?.optString("title").isNullOrBlank()) {
                    prefs.edit().putString(book.key, JSONObject().put("version", 3).put("fetched", System.currentTimeMillis()).toString()).apply()
                }
            }
        }
    }

    private suspend fun openLibrary(book: Audiobook): BookDetails? {
        val url = "https://openlibrary.org/search.json?title=${Uri.encode(book.title)}" +
            (if (book.author.isNotBlank()) "&author=${Uri.encode(book.author)}" else "") +
            "&fields=key,title,author_name,cover_i&limit=5&lang=ru"
        val docs = json(url)?.optJSONArray("docs") ?: return null
        for (index in 0 until docs.length()) {
            val doc = docs.optJSONObject(index) ?: continue
            val title = doc.optString("title")
            val names = doc.optJSONArray("author_name")
            val author = (0 until (names?.length() ?: 0)).mapNotNull { names?.optString(it)?.takeIf(String::isNotBlank) }.joinToString(", ")
            if (!matchesBook(book.title, book.author, title, author)) continue
            val key = doc.optString("key").takeIf { it.matches(Regex("/works/OL[0-9]+W")) } ?: continue
            val work = json("https://openlibrary.org$key.json")
            val description = when (val value = work?.opt("description")) {
                is String -> value
                is JSONObject -> value.optString("value")
                else -> ""
            }
            val coverId = doc.optLong("cover_i").takeIf { it > 0 }
            val cover = coverId?.let { cacheCover(book.key, "https://covers.openlibrary.org/b/id/$it-M.jpg?default=false") }
            return BookDetails(title, author, description, cover, "Open Library", "https://openlibrary.org$key", System.currentTimeMillis())
        }
        return null
    }

    private suspend fun googleBooks(book: Audiobook): BookDetails? {
        val query = listOf(book.title, book.author).filter { it.isNotBlank() }.joinToString(" ")
        val items = json("https://www.googleapis.com/books/v1/volumes?q=${Uri.encode(query)}&maxResults=5&printType=books")
            ?.optJSONArray("items") ?: return null
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val volume = item.optJSONObject("volumeInfo") ?: continue
            val names = volume.optJSONArray("authors")
            val author = (0 until (names?.length() ?: 0)).mapNotNull { names?.optString(it)?.takeIf(String::isNotBlank) }.joinToString(", ")
            val title = volume.optString("title")
            if (!matchesBook(book.title, book.author, title, author)) continue
            val id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: continue
            val coverUrl = volume.optJSONObject("imageLinks")?.optString("thumbnail")?.replace("http://", "https://")
                ?.takeIf { Uri.parse(it).host in setOf("books.google.com", "books.googleusercontent.com") }
            val description = android.text.Html.fromHtml(volume.optString("description"), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            return BookDetails(title, author, description, coverUrl?.let { cacheCover(book.key, it) }, "Google Books",
                "https://books.google.com/books?id=$id", System.currentTimeMillis())
        }
        return null
    }

    private suspend fun json(url: String): JSONObject? = requests.withLock {
        delay((1100 - (System.currentTimeMillis() - lastRequest)).coerceAtLeast(0))
        lastRequest = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching { http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { response -> if (response.isSuccessful) response.body?.string()?.let(::JSONObject) else null }
            }.getOrNull().also { if (it == null) requestFailed = true }
        }
    }

    private fun cacheCover(key: String, url: String): String? = runCatching {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(File(context.filesDir, "book-covers").apply { mkdirs() }, "$hash.jpg")
        if (!file.exists()) {
            http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body?.byteStream()?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 2_000_000) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: return@runCatching null
                if (bytes.isEmpty() || bytes.size > 2_000_000) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                file.writeBytes(bytes)
            }
        }
        Uri.fromFile(file).toString()
    }.getOrNull()

    companion object {
        private const val USER_AGENT = "MyMusic/1.0 (https://github.com/dantefq/mymusic-android)"
        private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        private val requests = Mutex()
        private var lastRequest = 0L
    }
}
