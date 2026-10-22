package com.example.mymusic

import android.content.ComponentName
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
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

/** Isolated emulator fixtures exercise the real button, service refill, likes, stats and root navigation. */
@RunWith(AndroidJUnit4::class)
class SmartMixFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun diverseRotationLikesInsightsAndHomeReset() = runBlocking {
        val db = MusicDb.get(context)
        val uris = mutableListOf<android.net.Uri>()
        val fixtureIds = mutableListOf<Long>()
        val prefs = context.getSharedPreferences("playback", 0)
        val oldMode = prefs.getString("queue_mode", "off")
        val oldSleep = prefs.getLong("sleep_until", 0)
        val rotationPrefs = context.getSharedPreferences("smart_mix_rotation", 0)
        val oldUsed = rotationPrefs.getStringSet("used", emptySet()).orEmpty().toSet()
        val oldRecent = rotationPrefs.getString("recent", "")
        val likesPrefs = context.getSharedPreferences("favorites", 0)
        val oldLikes = likesPrefs.getStringSet("tracks", emptySet()).orEmpty().toSet()
        var scenario: ActivityScenario<MainActivity>? = null
        var player: MediaController? = null
        var queue = emptyList<MediaItem>(); var position = 0L; var index = 0
        var playing = false; var repeat = 0; var shuffle = false
        val stamp = System.currentTimeMillis()
        try {
            val bytes = wav()
            val tracks = (1..60).map { number ->
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "QA Mix $stamp $number.wav")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MyMusic Mix QA/")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }))
                uris += uri; fixtureIds += uri.lastPathSegment!!.toLong()
                context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                Track(fixtureIds.last(), uri.toString(), "QA Mix $number", "QA Artist ${number % 10}", "QA Mix Album", 30000, "audio/wav", folderPath = "Music/MyMusic Mix QA/", fileName = "QA Mix $stamp $number.wav")
            }
            LocalLibrary(context, db.tracks()).scan(true)
            db.tracks().putAll(tracks)
            val music = db.tracks().all().filterNot { it.isAudiobook }
            rotationPrefs.edit().clear().commit()
            prefs.edit().putString("queue_mode", "off").putLong("sleep_until", 0).commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            player = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().get(15, TimeUnit.SECONDS)
            val controller = player!!
            main {
                queue = (0 until controller.mediaItemCount).map(controller::getMediaItemAt)
                position = controller.currentPosition; index = controller.currentMediaItemIndex.coerceAtLeast(0)
                playing = controller.playWhenReady; repeat = controller.repeatMode; shuffle = controller.shuffleModeEnabled
                controller.pause(); controller.shuffleModeEnabled = true; controller.repeatMode = Player.REPEAT_MODE_ALL
            }
            Thread.sleep(2000)
            tapText(context.localized("Library"))
            waitFor("Smart mix hero must be visible") { texts(context.localized("Smart Mix")).isNotEmpty() }
            screenshot("mix-home.png")
            fun queueIds(): List<Long> { var ids = emptyList<Long>(); main { ids = (0 until controller.mediaItemCount).mapNotNull { controller.getMediaItemAt(it).mediaId.toLongOrNull() } }; return ids }
            tapText(context.localized("Smart Mix"))
            waitFor("First smart queue must start") { prefs.getString("queue_mode", "") == "smart" && queueIds().size == 24 }
            main { controller.pause(); assertFalse(controller.shuffleModeEnabled); assertEquals(Player.REPEAT_MODE_OFF, controller.repeatMode) }
            val first = queueIds()
            Thread.sleep(400)
            tapText(context.localized("Smart Mix"))
            waitFor("A new start must use different tracks") { queueIds().size == 24 && queueIds().firstOrNull() != first.first() }
            main { controller.pause() }
            val second = queueIds()
            assertTrue("Fresh starts must not repeat the previous batch", first.toSet().intersect(second.toSet()).isEmpty())
            main { controller.seekTo(22, 0); controller.play() }
            waitFor("Background service must extend the smart queue") { queueIds().size > 24 }
            main { controller.pause() }
            val extended = queueIds()
            val appended = extended.drop(24)
            assertTrue("Refill must exclude current and queued tracks", appended.none { it in second.takeLast(2) })
            val cycle = first + second + appended
            assertEquals("The first rotation must not repeat before exhaustion", minOf(music.size, cycle.size), cycle.take(music.size).toSet().size)
            val chosen = db.tracks().get(extended[22])!!
            waitFor("Mini player must expose the active title") { texts(chosen.title).isNotEmpty() }
            tapText(chosen.title)
            waitFor("Full player must open") { descriptions(context.localized("Favorite")).isNotEmpty() }
            if (chosen.id in favoriteTrackIds(context)) toggleFavoriteTrack(context, chosen.id)
            tap(descriptions(context.localized("Favorite")).first())
            waitFor("Heart must persist the song") { chosen.id in favoriteTrackIds(context) }
            screenshot("mix-player.png")
            tapText(context.localized("Library"))
            waitFor("Library from full player must return to hero") { texts(context.localized("Smart Mix")).isNotEmpty() }
            scrollToText(context.localized("Liked tracks")); tapText(context.localized("Liked tracks"))
            waitFor("Liked collection must open") { texts(context.localized("Every heart in the player saves a song here")).isNotEmpty() }
            assertTrue(texts(chosen.title).isNotEmpty())
            screenshot("mix-liked.png")
            tap(descriptions(context.localized("Remove from liked tracks")).first())
            waitFor("Removing heart must update stored collection") { chosen.id !in favoriteTrackIds(context) }
            tapText(context.localized("Library"))
            scrollToText(context.localized("Statistics")); tapText(context.localized("Statistics"))
            waitFor("Listening insights must open") { texts(context.localized("Your listening story")).isNotEmpty() }
            waitFor("Statistics must use actual history") { texts(context.localized("Your rhythm")).isNotEmpty() }
            screenshot("mix-insights.png")
            tapText(context.localized("Month"))
            tapText(context.localized("Library"))
            waitFor("Library from statistics must return to hero") { texts(context.localized("Smart Mix")).isNotEmpty() }
            tap(descriptions(context.localized("Search")).first())
            waitFor("Search field must open") { nodes().any { it.isEditable && it.isVisibleToUser } }
            nodes().first { it.isEditable && it.isVisibleToUser }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "QA Mix")
            })
            Thread.sleep(600)
            tapText(context.localized("Library"))
            waitFor("Library must clear search and scroll to the start") {
                texts(context.localized("Smart Mix")).isNotEmpty() && nodes().none { it.isEditable && it.isVisibleToUser }
            }
            Thread.sleep(650) // Let IME dismissal and the home reset finish before scrolling again.
            scrollToText(context.localized("All Tracks")); tapText(context.localized("All Tracks"))
            repeat(10) {
                if (texts(context.localized("Smart Mix")).isNotEmpty()) {
                    nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(350)
                }
            }
            assertTrue("Fixture list must scroll away from the hero", texts(context.localized("Smart Mix")).isEmpty())
            tapText(context.localized("Library"))
            waitFor("Repeated Library tap must reset scroll on the same page") { texts(context.localized("Smart Mix")).isNotEmpty() }
        } finally {
            main { player?.let { controller ->
                controller.pause()
                if (queue.isEmpty()) controller.clearMediaItems() else {
                    controller.setMediaItems(queue, index, position); controller.shuffleModeEnabled = shuffle; controller.repeatMode = repeat
                    controller.prepare(); controller.playWhenReady = playing
                }
                controller.release()
            } }
            scenario?.close()
            prefs.edit().putString("queue_mode", oldMode).putLong("sleep_until", oldSleep).commit()
            rotationPrefs.edit().clear().putStringSet("used", oldUsed).putString("recent", oldRecent).commit()
            likesPrefs.edit().putStringSet("tracks", oldLikes).commit()
            uris.forEach { context.contentResolver.delete(it, null, null) }
            db.tracks().deleteIds(fixtureIds); db.plays().deleteTracks(fixtureIds)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::flatten).orEmpty() }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::flatten).orEmpty()
    }
    private fun texts(value: String) = nodes().filter { it.isVisibleToUser && it.text?.toString() == value }
    private fun descriptions(value: String) = nodes().filter { it.isVisibleToUser && it.contentDescription?.toString() == value }
    private fun tapText(value: String) {
        waitFor("Text must be ready before tapping: $value") { texts(value).isNotEmpty() }
        tap(texts(value).last())
    }
    private fun tap(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        error("No clickable parent for ${node.text ?: node.contentDescription}")
    }
    private fun scrollToText(value: String) {
        repeat(7) {
            if (texts(value).isNotEmpty()) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(350)
        }
        assertTrue("Missing text after scrolling: $value", texts(value).isNotEmpty())
    }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(80)
        assertTrue(message, condition())
    }
    private fun screenshot(name: String) {
        Thread.sleep(650)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun wav(): ByteArray {
        val rate = 8000; val length = rate * 30 * 2
        return ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + length); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(length)
        }.array()
    }
}
