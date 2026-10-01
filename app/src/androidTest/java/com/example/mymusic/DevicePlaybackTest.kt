package com.example.mymusic

import android.content.ContentValues
import android.content.ComponentName
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
