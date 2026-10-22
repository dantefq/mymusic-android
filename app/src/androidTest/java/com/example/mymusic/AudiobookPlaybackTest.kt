package com.example.mymusic

import android.content.ComponentName
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Real Media3 playback, queue replacement, independent resume buttons, and automatic chapter handoff. */
@RunWith(AndroidJUnit4::class)
class AudiobookPlaybackTest {
    @Test fun eachBookResumesAfterMusicAndChaptersAdvanceInOrder() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = MusicDb.get(context)
        val uris = mutableListOf<Uri>()
        val stamp = System.currentTimeMillis()
        val bookmarks = BookBookmarks(context)
        val playbackPrefs = context.getSharedPreferences("playback", 0)
        val oldMode = playbackPrefs.getString("queue_mode", "off")
        val oldCrossfade = playbackPrefs.getInt("crossfade", 0)
        val oldSleep = playbackPrefs.getLong("sleep_until", 0)
        val oldSleepChapter = playbackPrefs.getLong("sleep_chapter", 0)
        val oldSleepBook = playbackPrefs.getString("sleep_book", "")
        var controller: MediaController? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var previousQueue = emptyList<MediaItem>()
        var previousIndex = 0
        var previousPosition = 0L
        var previousRepeat = 0
        var previousShuffle = false
        var previousPlaying = false
        var fixtureTracks = emptyList<Track>()
        var books = emptyList<Audiobook>()
        try {
            // Recover only this test's timestamped fixtures after an externally interrupted run.
            val stale = db.tracks().all().filter {
                Regex("(?:Audiobooks/QA Story Alpha|Music/Аудиокниги/QA Story Beta|Music/QA) \\d{13}/").matches(it.folderPath)
            }
            groupAudiobooks(stale).forEach {
                context.getSharedPreferences("book_bookmarks", 0).edit().remove(it.key).commit()
                context.getSharedPreferences("book_metadata", 0).edit().remove(it.key).commit()
            }
            stale.forEach { context.contentResolver.delete(Uri.parse(it.uri), null, null) }
            db.tracks().deleteIds(stale.map { it.id }); db.plays().deleteTracks(stale.map { it.id })
            val files = listOf("01.wav", "02.wav", "01.wav", "QA Music.wav")
            fixtureTracks = files.mapIndexed { index, name ->
                val folder = if (index < 2) "Audiobooks/QA Story Alpha $stamp/" else if (index == 2) "Music/Аудиокниги/QA Story Beta $stamp/" else "Music/QA $stamp/"
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }))
                uris += uri
                context.contentResolver.openOutputStream(uri)!!.use { it.write(wav()) }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                Track(uri.lastPathSegment!!.toLong(), uri.toString(), name, "QA Author", "", 10_000, "audio/wav",
                    isAudiobook = index < 3, folderPath = folder, fileName = name)
            }
            LocalLibrary(context, db.tracks()).scan(true)
            fixtureTracks.take(3).forEach { assertTrue("English and Russian book folders must scan as audiobooks", db.tracks().get(it.id)!!.isAudiobook) }
            db.tracks().putAll(fixtureTracks)
            books = groupAudiobooks(fixtureTracks)
            val alpha = books.first { it.chapters.size == 2 }
            val beta = books.first { it.chapters.size == 1 }
            // An intentional negative cache keeps the integration fixtures fully offline.
            val metadataPrefs = context.getSharedPreferences("book_metadata", 0)
            books.forEach { metadataPrefs.edit().putString(it.key, "{\"version\":3,\"fetched\":${System.currentTimeMillis()}}").commit() }
            playbackPrefs.edit().putString("queue_mode", "off").putInt("crossfade", 5)
                .putLong("sleep_until", 0).remove("sleep_chapter").remove("sleep_book").commit()
            if (InstrumentationRegistry.getArguments().getString("phoneLibrary") == "true") {
                // MIUI can indefinitely defer ActivityScenario's background start. Shell launching
                // uses the same normal launcher intent as an ADB/manual launch on the attached phone.
                instrumentation.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.example.mymusic -n com.example.mymusic/.MainActivity").use { }
            } else scenario = ActivityScenario.launch(MainActivity::class.java)
            Thread.sleep(2500)
            controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .buildAsync().get(15, TimeUnit.SECONDS)
            val player = controller!!
            fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
            fun currentId(): Long? { var id: Long? = null; onMain { id = player.currentMediaItem?.mediaId?.toLongOrNull() }; return id }
            onMain {
                previousQueue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                previousIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                previousPosition = player.currentPosition
                previousPlaying = player.playWhenReady
                previousRepeat = player.repeatMode
                previousShuffle = player.shuffleModeEnabled
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItems(alpha.chapters.mapIndexed { i, chapter -> bookItem(alpha, chapter, i == alpha.chapters.lastIndex) })
                player.prepare(); player.play()
            }
            waitFor("First chapter must start") { currentId() == alpha.chapters[0].id }
            playbackPrefs.edit().putLong("sleep_chapter", alpha.chapters[0].id).putString("sleep_book", alpha.key).commit()
            onMain { player.seekTo(9200) }
            waitFor("End of chapter must automatically advance to chapter 2") { currentId() == alpha.chapters[1].id }
            waitFor("End-of-chapter timer must pause before chapter 2 plays") {
                var paused = false; onMain { paused = !player.playWhenReady }; paused && playbackPrefs.getLong("sleep_chapter", 0) == 0L
            }
            assertTrue("Natural completion marks the previous chapter as listened", alpha.chapters[0].id in bookmarks.get(alpha.key)!!.completedChapters)
            onMain { assertTrue("Books must not be crossfaded", player.currentPosition < 1700); player.pause(); player.seekTo(3700) }
            waitFor("Second chapter bookmark must be saved") { bookmarks.get(alpha.key)?.positionMs == 3700L }
            bookmarks.setChapterCompleted(alpha, alpha.chapters[0].id, false)
            assertEquals("Manual marks must preserve resume position", 3700L, bookmarks.get(alpha.key)!!.positionMs)
            assertFalse(chapterCompleted(alpha, bookmarks.get(alpha.key), alpha.chapters[0].id))
            bookmarks.setChapterCompleted(alpha, alpha.chapters[0].id, true)
            val music = fixtureTracks.last()
            fun switchToMusic() = onMain {
                player.setMediaItem(MediaItem.Builder().setMediaId(music.id.toString()).setUri(music.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("QA Music").build()).build())
                player.prepare(); player.pause()
            }
            switchToMusic()
            Thread.sleep(250)
            assertEquals("Music must preserve the book position", 3700L, BookBookmarks(context).get(alpha.key)!!.positionMs)
            onMain {
                player.setMediaItem(bookItem(beta, beta.chapters.first(), true)); player.prepare(); player.pause()
            }
            waitFor("Second book queue must reach the service before seeking") {
                var ready = false
                onMain { ready = player.currentMediaItem?.mediaId == beta.chapters.first().id.toString() && player.playbackState == Player.STATE_READY }
                ready
            }
            onMain { player.seekTo(2300) }
            try {
                waitFor("Second book gets its own bookmark") { bookmarks.get(beta.key)?.positionMs == 2300L }
            } catch (failure: AssertionError) {
                var actual = ""
                onMain { actual = "id=${player.currentMediaItem?.mediaId}, position=${player.currentPosition}, requested=${player.playWhenReady}" }
                throw AssertionError("Second book bookmark: ${bookmarks.get(beta.key)}; player: $actual", failure)
            }
            switchToMusic()
            assertEquals(3700L, bookmarks.get(alpha.key)!!.positionMs)
            assertEquals(2300L, bookmarks.get(beta.key)!!.positionMs)
            screenshot("qa-music.png")

            fun texts(value: String) = nodes().filter { it.isVisibleToUser && it.text?.toString() == value }
            waitFor("Audiobooks navigation must be available") { texts(context.localized("Audiobooks")).isNotEmpty() }
            tap(texts(context.localized("Audiobooks")).first())
            waitFor("Books must appear as folders on the shelf") { texts(alpha.title).isNotEmpty() }
            screenshot("qa-bookshelf.png")
            tap(texts(alpha.title).first())
            waitFor("Book detail must show a resume button") { texts(context.localized("Continue listening")).isNotEmpty() && texts(context.localized("Start over")).isNotEmpty() }
            screenshot("qa-book-detail.png")
            tap(texts(context.localized("Continue listening")).first())
            waitFor("Alpha button must resume chapter 2") { currentId() == alpha.chapters[1].id }
            onMain {
                assertEquals("Resume queue must contain only this book", 2, player.mediaItemCount)
                assertTrue("Resume must seek to the saved position", player.currentPosition in 3700..5000)
                assertFalse(player.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
                assertNotNull("Missing covers must get a cached illustration in the player", player.currentMediaItem?.mediaMetadata?.artworkUri)
                assertEquals("Player must carry the book author", "QA Author", player.currentMediaItem?.mediaMetadata?.artist?.toString())
                player.pause()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Back from book must return to bookshelf") { texts(context.localized("Your bookshelf")).isNotEmpty() }
            Thread.sleep(400)
            repeat(5) {
                if (texts(beta.title).isEmpty()) {
                    swipeUp()
                    Thread.sleep(350)
                }
            }
            waitFor("Other book must be available separately") { texts(beta.title).isNotEmpty() }
            tap(texts(beta.title).first())
            waitFor("Other book detail must open before pressing its resume button") {
                texts(context.localized("Start over")).isNotEmpty() && texts(beta.title).isNotEmpty() &&
                    texts(context.localized("Continue listening")).size == 1
            }
            screenshot("qa-beta-detail.png")
            tap(texts(context.localized("Continue listening")).first())
            waitFor("Beta button must resume Beta, not Alpha") { currentId() == beta.chapters.first().id }
            onMain {
                assertEquals(1, player.mediaItemCount)
                assertTrue(player.currentPosition in 2300..3600)
                player.pause()
            }
            val title = "${beta.title} · ${context.localized("Chapter")} 1"
            waitFor("Mini-player must include book title and chapter") { texts(title).isNotEmpty() }
            tap(texts(title).first())
            fun descriptions(value: String) = nodes().filter { it.isVisibleToUser && it.contentDescription?.toString() == value }
            waitFor("Audiobook player must have its sleep timer") { descriptions(context.localized("Sleep timer")).isNotEmpty() }
            assertTrue("Audiobooks have no output-routing button", descriptions(context.localized("Output device")).isEmpty())
            screenshot("qa-book-player.png")
            tap(descriptions(context.localized("Sleep timer")).first())
            waitFor("Sleep timer presets must open") { texts(context.getString(R.string.book_minutes, 15L)).isNotEmpty() }
            screenshot("qa-sleep-timer.png")
            tap(texts(context.getString(R.string.book_minutes, 15L)).first())
            waitFor("15-minute preset must be saved") { playbackPrefs.getLong("sleep_until", 0) - System.currentTimeMillis() in 890000..900000 }
            // Expire the same timer quickly to exercise the background service.
            playbackPrefs.edit().putLong("sleep_until", System.currentTimeMillis() + 300).commit()
            onMain { player.play() }
            waitFor("Timed sleep must pause playback") {
                var paused = false; onMain { paused = !player.playWhenReady }; paused && playbackPrefs.getLong("sleep_until", 0) == 0L
            }
            onMain { player.seekTo(9500); player.play() }
            waitFor("Last chapter marks only this book as finished") { bookmarks.get(beta.key)?.completed == true }
            assertFalse(bookmarks.get(alpha.key)!!.completed)
            switchToMusic()
            Thread.sleep(250)
            assertTrue("Switching to music must preserve finished status", BookBookmarks(context).get(beta.key)!!.completed)
        } finally {
            instrumentation.runOnMainSync {
                controller?.let { player ->
                    player.stop()
                    if (previousQueue.isEmpty()) player.clearMediaItems() else {
                        player.setMediaItems(previousQueue, previousIndex, previousPosition)
                        player.shuffleModeEnabled = previousShuffle; player.repeatMode = previousRepeat
                        player.prepare(); player.playWhenReady = previousPlaying
                    }
                    player.release()
                }
            }
            scenario?.close()
            playbackPrefs.edit().putString("queue_mode", oldMode).putInt("crossfade", oldCrossfade)
                .putLong("sleep_until", oldSleep).putLong("sleep_chapter", oldSleepChapter).putString("sleep_book", oldSleepBook).commit()
            uris.forEach { context.contentResolver.delete(it, null, null) }
            db.tracks().deleteIds(fixtureTracks.map { it.id })
            db.plays().deleteTracks(fixtureTracks.map { it.id })
            books.forEach {
                context.getSharedPreferences("book_bookmarks", 0).edit().remove(it.key).commit()
                context.getSharedPreferences("book_metadata", 0).edit().remove(it.key).commit()
            }
        }
    }

    private fun bookItem(book: Audiobook, track: Track, last: Boolean): MediaItem = MediaItem.Builder()
        .setMediaId(track.id.toString()).setUri(track.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(track.title)
            .setAlbumTitle(book.title).setArtist(book.author).setExtras(Bundle().apply {
                putString(BOOK_KEY, book.key); putBoolean(BOOK_LAST_CHAPTER, last)
            }).build()).build()

    private fun waitFor(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12_000
        while (!condition() && SystemClock.uptimeMillis() < until) Thread.sleep(80)
        assertTrue(message, condition())
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount)
            .flatMap { node.getChild(it)?.let(::flatten).orEmpty() }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::flatten).orEmpty()
    }

    private fun tap(node: AccessibilityNodeInfo) {
        // A partially visible card can extend under the mini-player; activate its semantic action.
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
            InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(this, true)
            recycle()
        }
    }

    private fun swipeUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * .5f
        val startY = metrics.heightPixels * .73f
        val endY = metrics.heightPixels * .32f
        val start = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val action = if (step == 0) MotionEvent.ACTION_DOWN else if (step == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, startY + (endY - startY) * step / 12, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(this, true)
                recycle()
            }
            Thread.sleep(20)
        }
    }

    private fun screenshot(name: String) {
        Thread.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun wav(): ByteArray {
        val rate = 8000; val dataSize = rate * 10 * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
            repeat(rate * 10) { putShort(0) }
        }.array()
    }
}
