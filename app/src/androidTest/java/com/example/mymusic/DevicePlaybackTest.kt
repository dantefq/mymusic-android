package com.example.mymusic

import android.content.ContentValues
import android.content.ComponentName
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.net.Uri
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Creates its own MediaStore files and removes them even if an assertion fails. */
@RunWith(AndroidJUnit4::class)
class DevicePlaybackTest {
    @Test fun clockSeekPauseAndVerifiedDuplicateFiles() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = MusicDb.get(context)
        val uris = mutableListOf<Uri>()
        val ids = mutableListOf<Long>()
        var player: MediaController? = null
        var observer: ComposeView? = null
        var clock: PlaybackClock? = null
        var previousQueue = emptyList<MediaItem>()
        var previousIndex = 0
        var previousPosition = 0L
        var previousPlaying = false
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val prefs = context.getSharedPreferences("playback", 0)
        val oldQueueMode = prefs.getString("queue_mode", "off")
        try {
            // Let the initial library scan finish before inserting isolated fixtures.
            Thread.sleep(2000)
            val bytes = wav()
            val stamp = System.currentTimeMillis()
            val fixtureTracks = (0..1).map { index ->
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, "MyMusic QA $stamp $index.wav")
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MyMusic QA/")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }))
                uris.add(uri)
                context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                val id = uri.lastPathSegment!!.toLong().also { ids.add(it) }
                Track(id, uri.toString(), "QA Sync ${index + 1}", "QA Artist A; QA Artist B", "QA Album", 30_000, "audio/wav",
                    lyrics = "[00:00.00]QA intro\n[00:01.00]QA one second\n[00:03.00]QA three seconds\n[00:05.00]QA five seconds\n[00:10.00]QA ten seconds\n[00:20.00]QA twenty seconds", addedAt = stamp)
            }
            db.tracks().putAll(fixtureTracks)
            val groups = DuplicateScanner.scan(context, fixtureTracks)
            assertEquals(1, groups.size)
            val plan = duplicateCleanupPlan(groups, fixtureTracks.first().id, emptySet())
            assertEquals(fixtureTracks.last().id, plan.single().remove.id)
            assertNotNull(MediaStore.createDeleteRequest(context.contentResolver, listOf(uris.last())))
            player = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .buildAsync().get(10, TimeUnit.SECONDS)
            val controller = player!!
            scenario.onActivity { activity ->
                previousQueue = (0 until controller.mediaItemCount).map(controller::getMediaItemAt)
                previousIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
                previousPosition = controller.currentPosition
                previousPlaying = controller.playWhenReady
                observer = ComposeView(activity).apply { setContent { clock = rememberPlaybackClock(controller) } }
                (activity.window.decorView as ViewGroup).addView(observer, ViewGroup.LayoutParams(1, 1))
                prefs.edit().putString("queue_mode", "off").apply()
                controller.setMediaItem(MediaItem.Builder().setMediaId(fixtureTracks.first().id.toString()).setUri(uris.first())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("QA Sync 1").setArtist("QA Artist A; QA Artist B").setAlbumTitle("QA Album").build()).build())
                controller.prepare()
            }
            Thread.sleep(1000)
            scenario.onActivity { controller.seekTo(3100); controller.pause() }
            Thread.sleep(200)
            instrumentation.runOnMainSync {
                assertNotNull(clock)
                assertTrue("Paused seek must update the display immediately", kotlin.math.abs(clock!!.position - 3100) < 150)
                assertEquals(2, lyricIndex(parseLrc(fixtureTracks.first().lyrics!!), clock!!.position))
                controller.seekTo(0); controller.play()
            }
            Thread.sleep(1400)
            instrumentation.runOnMainSync {
                assertTrue("Visible clock must follow the audio position", kotlin.math.abs(controller.currentPosition - clock!!.position) < 150)
                assertEquals(1, lyricIndex(parseLrc(fixtureTracks.first().lyrics!!), clock!!.position))
                controller.pause()
            }
            verifySongListsAndPlayer(instrumentation, controller, fixtureTracks, clock!!)
            val inspectionSeconds = InstrumentationRegistry.getArguments().getString("inspectionSeconds", "0").toInt().coerceIn(0, 240)
            if (inspectionSeconds > 0) Thread.sleep(inspectionSeconds * 1000L)
        } finally {
            scenario.onActivity { activity ->
                player?.let { controller ->
                    controller.stop()
                    if (previousQueue.isNotEmpty()) {
                        controller.setMediaItems(previousQueue, previousIndex, previousPosition)
                        controller.prepare(); controller.playWhenReady = previousPlaying
                    } else controller.clearMediaItems()
                    controller.release()
                }
                observer?.let { (activity.window.decorView as ViewGroup).removeView(it) }
            }
            prefs.edit().putString("queue_mode", oldQueueMode).apply()
            uris.forEach { context.contentResolver.delete(it, null, null) }
            db.tracks().deleteIds(ids)
            db.plays().deleteTracks(ids)
            ids.forEach { db.playlists().removeTrackReferences(it); context.getSharedPreferences("lyrics_timing", 0).edit().remove("$it").apply() }
            scenario.close()
        }
    }

    private fun verifySongListsAndPlayer(instrumentation: Instrumentation, player: MediaController,
        tracks: List<Track>, clock: PlaybackClock) {
        val context = instrumentation.targetContext
        fun nodes(): List<AccessibilityNodeInfo> {
            fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
                listOf(node) + (0 until node.childCount).flatMap { index ->
                    node.getChild(index)?.let(::flatten).orEmpty()
                }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::flatten).orEmpty()
        }
        fun waitFor(description: String, predicate: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 8000
            while (!predicate() && SystemClock.uptimeMillis() < end) Thread.sleep(50)
            assertTrue(description, predicate())
        }
        fun text(value: String) = nodes().filter { it.isVisibleToUser && it.text?.toString() == value }
        fun description(value: String) = nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == value }
        fun tap(node: AccessibilityNodeInfo, horizontalFraction: Float = .5f) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            val x = bounds.left + bounds.width() * horizontalFraction
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, bounds.exactCenterY(), 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(this, true))
                    recycle()
                }
            }
        }
        fun back() {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        fun currentId(): Long? {
            var id: Long? = null
            instrumentation.runOnMainSync { id = player.currentMediaItem?.mediaId?.toLongOrNull() }
            return id
        }
        waitFor("Library must be visible") { text(context.localized("All Tracks")).isNotEmpty() }
        if (text(tracks[1].title).isEmpty()) tap(text(context.localized("All Tracks")).first())
        waitFor("Expanded list must show fixture songs") { text(tracks[1].title).isNotEmpty() }
        tap(text(tracks[1].title).first())
        waitFor("Song title must play instead of navigating") { currentId() == tracks[1].id }
        assertTrue(text(context.localized("All Tracks")).isNotEmpty())
        val credits = artistNames(tracks[0]).joinToString(" · ")
        tap(text(credits).first())
        waitFor("Song artist credits must play instead of navigating") { currentId() == tracks[0].id }
        assertTrue(text(context.localized("All Tracks")).isNotEmpty())
        instrumentation.runOnMainSync { player.pause() }
        // The lower occurrence is the mini-player, below the library's song row.
        waitFor("Mini-player metadata must follow the selected song") { text(tracks[0].title).size >= 2 }
        val miniTitle = text(tracks[0].title).maxBy { Rect().also(it::getBoundsInScreen).top }
        tap(miniTitle)
        waitFor("Mini-player title must open full-screen playback") { description(context.localized("Back to library")) }
        Thread.sleep(400)
        tap(text(credits).single(), .2f)
        waitFor("Full-screen artist link must open the artist") { text("QA Artist A").isNotEmpty() && description(context.localized("Back")) }
        back()
        waitFor("Artist Back must return to full-screen playback") { description(context.localized("Back to library")) }
        Thread.sleep(400)
        tap(text("QA Album").single())
        waitFor("Full-screen album link must open the album") { text("QA Album").isNotEmpty() && description(context.localized("Back")) }
        back()
        waitFor("Album Back must return to full-screen playback") { description(context.localized("Back to library")) }
        Thread.sleep(400)
        val seek = nodes().first { it.rangeInfo?.max?.let { max -> max > 20_000 } == true }
        assertTrue("Seek rail must support accessible seeking", seek.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 5500f) }))
        waitFor("Paused seeking must update both player and clock") {
            var correct = false
            instrumentation.runOnMainSync {
                correct = kotlin.math.abs(player.currentPosition - 5500) < 150 && kotlin.math.abs(clock.position - 5500) < 150
            }
            correct
        }
    }

    private fun wav(): ByteArray {
        val sampleRate = 8000
        val dataSize = sampleRate * 30 * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
        buffer.putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        repeat(sampleRate * 30) { index ->
            val pulse = index % sampleRate < 800
            buffer.putShort(if (pulse) (kotlin.math.sin(index * 2.0 * Math.PI * 440 / sampleRate) * 2500).toInt().toShort() else 0)
        }
        return buffer.array()
    }
}
