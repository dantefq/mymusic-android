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
            // Wait for actual audio progress rather than assuming decoder startup takes <400 ms.
            val playbackDeadline = SystemClock.uptimeMillis() + 15000
            var advanced = false
            while (!advanced && SystemClock.uptimeMillis() < playbackDeadline) {
                instrumentation.runOnMainSync { advanced = controller.isPlaying && controller.currentPosition >= 1200 }
                if (!advanced) Thread.sleep(80)
            }
            assertTrue("Audio must cross the first lyric timestamp", advanced)
            instrumentation.runOnMainSync {
                assertTrue("Visible clock must follow the audio position", kotlin.math.abs(controller.currentPosition - clock!!.position) < 150)
                assertTrue("Lyrics must advance with playback", lyricIndex(parseLrc(fixtureTracks.first().lyrics!!), clock!!.position) >= 1)
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
            // UiAutomation retains node snapshots across animation/semantics updates.
            // Always assert against the current app tree, not a cached old action label.
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
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
            if (horizontalFraction == .5f) {
                var target: AccessibilityNodeInfo? = node
                while (target != null) {
                    if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    target = target.parent
                }
            }
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
        fun reveal(value: String) {
            repeat(8) {
                if (text(value).isNotEmpty()) return
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(250)
            }
        }
        waitFor("Library must be visible") { description(context.localized("Search")) }
        reveal(context.localized("All Tracks"))
        if (text(tracks[1].title).isEmpty()) tap(text(context.localized("All Tracks")).first())
        reveal(tracks[1].title)
        waitFor("Expanded list must show fixture songs") { text(tracks[1].title).isNotEmpty() }
        tap(text(tracks[1].title).first())
        waitFor("Song title must play instead of navigating") { currentId() == tracks[1].id }
        assertTrue(description(context.localized("Search")))
        val credits = artistNames(tracks[0]).joinToString(" · ")
        fun libraryRow(): AccessibilityNodeInfo? {
            for (title in text(tracks[0].title)) {
                var parent: AccessibilityNodeInfo? = title
                while (parent != null && !parent.isClickable) parent = parent.parent
                if (parent != null && !parent.isLongClickable) return parent
            }
            return null
        }
        // The same credits also appear in the mini-player, where tapping correctly
        // opens the player. Select credits inside this exact library song row.
        for (attempt in 0..8) {
            if (libraryRow() != null) break
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            Thread.sleep(200)
        }
        val rowBounds = Rect().also(requireNotNull(libraryRow())::getBoundsInScreen)
        tap(text(credits).first { rowBounds.contains(Rect().also(it::getBoundsInScreen)) })
        waitFor("Song artist credits must play instead of navigating") { currentId() == tracks[0].id }
        assertTrue(description(context.localized("Search")))
        instrumentation.runOnMainSync { player.pause() }
        // Exercise the collapsed transport itself, including fast alternating skips.
        // A single metadata tree must follow the real service without opening the player.
        instrumentation.runOnMainSync {
            player.setMediaItems(tracks.map { track -> MediaItem.Builder().setMediaId(track.id.toString()).setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()).build() })
            player.prepare(); player.pause()
        }
        fun miniControl(label: String): AccessibilityNodeInfo {
            val value = context.localized(label)
            try {
                waitFor("Mini control must reflect $label") {
                    nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == value }
                }
            } catch (failure: AssertionError) {
                var state = ""
                instrumentation.runOnMainSync {
                    val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                    if (activity != null) {
                        val getter = MainActivity::class.java.getDeclaredMethod("getController").apply { isAccessible = true }
                        val ui = getter.invoke(activity) as? MediaController
                        state = "ui requested=${ui?.playWhenReady} playing=${ui?.isPlaying} state=${ui?.playbackState} pos=${ui?.currentPosition}; test requested=${player.playWhenReady} playing=${player.isPlaying} state=${player.playbackState} pos=${player.currentPosition}"
                    }
                }
                val descriptions = nodes().filter { it.isVisibleToUser }.mapNotNull { it.contentDescription?.toString()?.takeIf(String::isNotBlank) }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.getExternalFilesDir(null), "mini-control-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                throw AssertionError("$label $state; descriptions=$descriptions", failure)
            }
            return nodes().filter { it.isVisibleToUser && it.contentDescription?.toString() == value }
                .maxBy { Rect().also(it::getBoundsInScreen).top }
        }
        waitFor("Mini test queue must reach the service before transport actions") {
            var ready = false
            instrumentation.runOnMainSync { ready = player.playbackState == Player.STATE_READY && player.mediaItemCount == tracks.size }
            ready
        }
        fun miniTitle(value: String): AccessibilityNodeInfo? = text(value).firstOrNull { node ->
            var parent = node.parent
            var belongsToMini = false
            while (parent != null) {
                if (parent.isLongClickable) { belongsToMini = true; break }
                parent = parent.parent
            }
            belongsToMini
        }
        repeat(4) {
            tap(miniControl("Next"))
            waitFor("Mini Next must select the second track") { currentId() == tracks[1].id }
            try {
                waitFor("Mini title must follow Next") { miniTitle(tracks[1].title) != null }
            } catch (failure: AssertionError) {
                val visible = nodes().filter { it.isVisibleToUser && it.packageName?.toString() == context.packageName }
                    .mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.takeLast(16)
                var state = ""
                instrumentation.runOnMainSync {
                    val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                    if (activity != null) {
                        val getter = MainActivity::class.java.getDeclaredMethod("getController").apply { isAccessible = true }
                        val ui = getter.invoke(activity) as? MediaController
                        state = "UI connected=${ui?.isConnected}, id=${ui?.currentMediaItem?.mediaId}, index=${ui?.currentMediaItemIndex}; test id=${player.currentMediaItem?.mediaId}, index=${player.currentMediaItemIndex}"
                    }
                }
                throw AssertionError("Mini title after Next; $state; visible app labels=$visible", failure)
            }
            tap(miniControl("Previous"))
            waitFor("Mini Previous must select the first track") { currentId() == tracks[0].id }
        }
        tap(miniControl("Play"))
        waitFor("Mini Play must produce real playback") { var active=false; instrumentation.runOnMainSync { active=player.isPlaying }; active }
        tap(miniControl("Pause"))
        waitFor("Mini Pause must reach the service") { var paused=false; instrumentation.runOnMainSync { paused=!player.playWhenReady }; paused }
        assertTrue("Mini controls must leave the library open", description(context.localized("Search")))
        // Identify the metadata by its long-clickable transport parent, not the
        // number of matching library rows currently visible on a particular screen.
        waitFor("Mini-player metadata must follow the selected song") { miniTitle(tracks[0].title) != null }
        tap(miniTitle(tracks[0].title)!!)
        waitFor("Mini-player title must open full-screen playback") { description(context.localized("Back to library")) }
        waitFor("Every credited artist must expose an accessible link") {
            nodes().any { node -> node.actionList.any { it.label?.toString() == "QA Artist A" } }
        }
        val artistLink = nodes().first { node -> node.actionList.any { it.label?.toString() == "QA Artist A" } }
        assertTrue(artistLink.performAction(artistLink.actionList.first { it.label?.toString() == "QA Artist A" }.id))
        waitFor("Full-screen artist link must open the artist") { text("QA Artist A").isNotEmpty() && description(context.localized("Back")) }
        back()
        waitFor("Artist Back must return to full-screen playback") { description(context.localized("Back to library")) }
        Thread.sleep(700)
        waitFor("Artist transition must finish before selecting the player album") { text("QA Album").size == 1 }
        tap(text("QA Album").single())
        waitFor("Full-screen album link must open the album") { text("QA Album").isNotEmpty() && description(context.localized("Back")) }
        back()
        waitFor("Album Back must return to full-screen playback") { description(context.localized("Back to library")) }
        waitFor("Full player seek rail must be ready after the album transition") {
            nodes().any { it.isVisibleToUser && it.rangeInfo?.max?.let { max -> max > 20_000 } == true }
        }
        val seek = nodes().first { it.isVisibleToUser && it.rangeInfo?.max?.let { max -> max > 20_000 } == true }
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
