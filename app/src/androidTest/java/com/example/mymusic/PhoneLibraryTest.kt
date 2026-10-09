package com.example.mymusic

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in check of the reported real library. No private database or preferences are exported. */
@RunWith(AndroidJUnit4::class)
class PhoneLibraryTest {
    @Test fun realNamesResumeAndVisiblePlaybackMotion() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneLibrary") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val tracks = MusicDb.get(context).tracks().all()
        val books = groupAudiobooks(tracks)
        val lun = books.single { it.key.endsWith("/Лунь") }
        val twilight = books.single { it.key.endsWith("/Сумерки Богов") }
        assertEquals("Лунь", lun.title); assertEquals(43, lun.chapters.size)
        assertEquals("Сумерки Богов", twilight.title); assertEquals(244, twilight.chapters.size)
        assertEquals(ChapterNumbers(25, null), chapterNumbers(lun.chapters[24]))
        val tag = LocalTagReader(context).read(android.net.Uri.parse(lun.chapters.first().uri), lun.chapters.first().id, null)
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Real books: Лунь (43), Сумерки Богов (244). Embedded author: ${tag.author}; album artist: ${tag.albumArtist}\n") })
        val bookmarkPrefs = context.getSharedPreferences("book_bookmarks", 0)
        val originalBookmarks = books.associate { it.key to bookmarkPrefs.getString(it.key, null) }
        val metadataPrefs = context.getSharedPreferences("book_metadata", 0)
        val originalMetadata = books.associate { it.key to metadataPrefs.getString(it.key, null) }
        // Keep provider/image changes out of pixel comparisons; restore these cache entries afterward.
        originalMetadata.forEach { (key, value) ->
            val cached = runCatching { org.json.JSONObject(value ?: "{}") }.getOrDefault(org.json.JSONObject())
                .put("version", 3).put("fetched", System.currentTimeMillis())
            metadataPrefs.edit().putString(key, cached.toString()).commit()
        }
        val testStarted = System.currentTimeMillis()
        val playedIds = mutableSetOf<Long>()
        val prefs = context.getSharedPreferences("playback", 0)
        val oldMode = prefs.getString("queue_mode", "off")
        val oldSleep = prefs.getLong("sleep_until", 0)
        val oldChapter = prefs.getLong("sleep_chapter", 0)
        val oldBook = prefs.getString("sleep_book", "")
        var player: MediaController? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var queue = emptyList<MediaItem>()
        var index = 0; var position = 0L; var wasPlaying = false; var shuffle = false; var repeat = 0
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            player = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .buildAsync().get(15, TimeUnit.SECONDS)
            val controller = player!!
            main {
                queue = (0 until controller.mediaItemCount).map(controller::getMediaItemAt)
                index = controller.currentMediaItemIndex.coerceAtLeast(0); position = controller.currentPosition
                wasPlaying = controller.playWhenReady; shuffle = controller.shuffleModeEnabled; repeat = controller.repeatMode
                controller.pause()
            }
            prefs.edit().putString("queue_mode", "off").putLong("sleep_until", 0).remove("sleep_chapter").remove("sleep_book").commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            Thread.sleep(2200)
            tapText(context.localized("Audiobooks"))
            waitFor("Correct Russian title must be visible") { texts("Лунь").isNotEmpty() }
            assertTrue("Broken filename-derived title must disappear", texts("glava001m").isEmpty())
            tapText("Лунь")
            waitFor("Book detail must open") { texts(context.localized("Start over")).isNotEmpty() }
            val expected = bookResume(lun, BookBookmarks(context).get(lun.key))
            playedIds += expected.track.id
            tapText(context.localized("Continue listening"))
            waitFor("Real book must resume its own chapter") {
                var correct = false; main { correct = controller.currentMediaItem?.mediaId == expected.track.id.toString() && controller.isPlaying }; correct
            }
            main {
                assertEquals(43, controller.mediaItemCount)
                assertTrue(kotlin.math.abs(controller.currentPosition - expected.positionMs) < 2500)
            }
            Thread.sleep(1000)
            val bookBounds = boundsWithDescription(context.localized("Book cover"))
            assertMotion("Book cover must move while playing", bookBounds, true)
            main { controller.pause() }
            waitFor("Book playback must pause") { var paused = false; main { paused = !controller.isPlaying && !controller.playWhenReady }; paused }
            Thread.sleep(1000)
            assertMotion("Book cover must stop on pause", bookBounds, false)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            tapText(context.localized("Library"))
            waitFor("Music hero must be visible") { descriptions(context.localized("Record player")).isNotEmpty() }
            val music = tracks.first { !it.isAudiobook && it.folderPath.startsWith("Music/") && it.durationMs > 30000 }
            playedIds += music.id
            main {
                controller.setMediaItem(MediaItem.Builder().setMediaId(music.id.toString()).setUri(music.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(music.title).setArtist(music.artist).build()).build())
                controller.prepare(); controller.play()
            }
            waitFor("Music must play") { var playing = false; main { playing = controller.isPlaying }; playing }
            Thread.sleep(1000)
            val recordBounds = boundsWithDescription(context.localized("Record player"))
            assertMotion("Record must rotate during music playback", recordBounds, true)
            main { controller.pause() }
            waitFor("Music playback must pause") { var paused = false; main { paused = !controller.isPlaying && !controller.playWhenReady }; paused }
            Thread.sleep(1000)
            assertMotion("Record must stop on pause", recordBounds, false)
        } finally {
            main {
                player?.let { controller ->
                    controller.pause()
                    if (queue.isEmpty()) controller.clearMediaItems() else {
                        controller.setMediaItems(queue, index, position); controller.shuffleModeEnabled = shuffle; controller.repeatMode = repeat
                        controller.prepare(); controller.playWhenReady = wasPlaying
                    }
                    controller.release()
                }
            }
            scenario?.close()
            Thread.sleep(300)
            bookmarkPrefs.edit().apply { originalBookmarks.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
            metadataPrefs.edit().apply { originalMetadata.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
            prefs.edit().putString("queue_mode", oldMode).putLong("sleep_until", oldSleep).putLong("sleep_chapter", oldChapter).putString("sleep_book", oldBook).commit()
            val temporaryEvents = MusicDb.get(context).plays().since(testStarted).filter { it.trackId in playedIds }.map { it.id }
            temporaryEvents.forEach { id -> MusicDb.get(context).openHelper.writableDatabase.execSQL("DELETE FROM plays WHERE id = ?", arrayOf(id)) }
            originalBookmarks.forEach { (key, value) -> assertEquals("Original book checkpoint must be restored", value, bookmarkPrefs.getString(key, null)) }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::flatten).orEmpty() }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::flatten).orEmpty()
    }
    private fun texts(value: String) = nodes().filter { it.isVisibleToUser && it.text?.toString() == value }
    private fun descriptions(value: String) = nodes().filter { it.isVisibleToUser && it.contentDescription?.toString() == value }
    private fun tapText(value: String) {
        waitFor("Control must be visible: $value") { texts(value).isNotEmpty() }
        var node: AccessibilityNodeInfo? = texts(value).first()
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        fail("Control has no click action: $value")
    }
    private fun boundsWithDescription(value: String): Rect = Rect().also { descriptions(value).first().getBoundsInScreen(it) }
    private fun assertMotion(message: String, bounds: Rect, moving: Boolean) {
        fun capture(): Bitmap {
            val full = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            return Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width(), bounds.height()).also { full.recycle() }
        }
        val first = capture(); Thread.sleep(700); val second = capture()
        var changed = 0
        for (y in 0 until first.height step 3) for (x in 0 until first.width step 3) {
            val a = first.getPixel(x, y); val b = second.getPixel(x, y)
            val difference = kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
            if (difference > 18) changed++ // Ignore GPU color dithering in otherwise identical frames.
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-${if (message.startsWith("Record")) "record" else "book"}-${if (moving) "moving" else "paused"}"
        listOf(first, second).forEachIndexed { index, bitmap ->
            java.io.File(context.getExternalFilesDir(null), "$name-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$name: $changed changed sample pixels\n") })
        first.recycle(); second.recycle()
        if (moving) assertTrue("$message: $changed pixels", changed > 30) else assertEquals(message, 0, changed)
    }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!predicate() && SystemClock.uptimeMillis() < until) Thread.sleep(100)
        assertTrue(message, predicate())
    }
}
