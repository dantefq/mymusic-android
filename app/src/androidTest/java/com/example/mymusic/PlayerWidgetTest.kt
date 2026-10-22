package com.example.mymusic

import android.Manifest
import android.app.ActivityManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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

/** A real Android widget host isolated from the user's launcher. Restores the original queue/bookmarks. */
@RunWith(AndroidJUnit4::class)
class PlayerWidgetTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun connect() = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().get(15, TimeUnit.SECONDS)

    @Test fun realWidgetControlsArtworkResizeAndResume() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val files = mutableListOf<File>()
        var player: MediaController? = null
        var scenario: ActivityScenario<WidgetHarnessActivity>? = null
        var host: AppWidgetHost? = null
        var view: AppWidgetHostView? = null
        var widgetId = -1
        var queue = emptyList<MediaItem>(); var index = 0; var position = 0L
        var playing = false; var repeat = 0; var shuffle = false
        val prefs = context.getSharedPreferences("playback", 0)
        val oldMode = prefs.getString("queue_mode", "off")
        val oldSleep = prefs.getLong("sleep_until", 0)
        val oldSleepChapter = prefs.getLong("sleep_chapter", 0)
        val oldSleepBook = prefs.getString("sleep_book", "")
        val bookPrefs = context.getSharedPreferences("book_bookmarks", 0)
        var originalBookmarks = emptyMap<String, String?>()
        val started = System.currentTimeMillis()
        fun text(id: Int): String { var value = ""; main { value = view?.findViewById<TextView>(id)?.text?.toString().orEmpty() }; return value }
        fun description(): String { var value = ""; main { value = view?.findViewById<android.view.View>(R.id.widget_play)?.contentDescription?.toString().orEmpty() }; return value }
        fun click(id: Int) = main { assertTrue("Widget control must be clickable", view!!.findViewById<android.view.View>(id).performClick()) }
        fun running(): Boolean = context.getSystemService(ActivityManager::class.java).getRunningServices(50).any {
            it.service.className == PlaybackService::class.java.name
        }
        try {
            player = connect()
            main {
                val controller = player!!
                queue = (0 until controller.mediaItemCount).map(controller::getMediaItemAt)
                index = controller.currentMediaItemIndex.coerceAtLeast(0); position = controller.currentPosition
                playing = controller.playWhenReady; repeat = controller.repeatMode; shuffle = controller.shuffleModeEnabled
                originalBookmarks = queue.mapNotNull { it.mediaMetadata.extras?.getString(BOOK_KEY) }.distinct()
                    .associateWith { bookPrefs.getString(it, null) }
                controller.pause()
            }
            prefs.edit().putString("queue_mode", "off").putLong("sleep_until", 0).remove("sleep_chapter").remove("sleep_book").commit()
            val wav = File(context.cacheDir, "widget-qa.wav").also { files += it; it.writeBytes(wav()) }
            fun cover(name: String, color: Int): Uri {
                val file = File(context.cacheDir, name).also { files += it }
                val bitmap = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                return Uri.fromFile(file)
            }
            val red = cover("widget-red.png",Color.RED); val blue = cover("widget-blue.png",Color.BLUE)
            fun item(id: String, title: String, artwork: Uri, book: Boolean = false) = MediaItem.Builder().setMediaId(id).setUri(Uri.fromFile(wav))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Widget QA")
                    .setArtworkUri(artwork).setExtras(Bundle().apply { if (book) putString(BOOK_KEY,"widget-qa-book") }).build()).build()
            val fixtures = listOf(item("widget-qa-a","Widget Alpha",red), item("widget-qa-b","Widget Beta",blue,true))
            main { player!!.setMediaItems(fixtures); player!!.shuffleModeEnabled=false; player!!.repeatMode=Player.REPEAT_MODE_OFF; player!!.prepare(); player!!.pause() }
            // Finish any previous MainActivity: its controller would legitimately keep
            // the service bound and make a cold-service test impossible.
            scenario = ActivityScenario.launch(Intent(context, WidgetHarnessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            scenario!!.onActivity { activity ->
                host = AppWidgetHost(activity, 903421)
                widgetId = host!!.allocateAppWidgetId()
            }
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,340)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,160); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,160)
            }
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
            try { assertTrue("QA host must bind only MyMusic's provider", manager.bindAppWidgetIdIfAllowed(widgetId,
                ComponentName(context, PlayerWidget::class.java),options)) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            scenario!!.onActivity { activity ->
                host!!.startListening()
                view = host!!.createView(activity,widgetId,manager.getAppWidgetInfo(widgetId)).apply { setPadding(0,0,0,0) }
                val density = activity.resources.displayMetrics.density
                activity.root.addView(view, FrameLayout.LayoutParams((340*density).toInt(),(160*density).toInt(),Gravity.CENTER))
                PlayerWidget.refresh(activity)
            }
            waitFor("Widget must show the actual queue title") { text(R.id.widget_title)=="Widget Alpha" }
            fun isColor(color: Int): Boolean { var matches=false; main {
                val bitmap=(view?.findViewById<ImageView>(R.id.widget_art)?.drawable as? BitmapDrawable)?.bitmap
                matches=bitmap?.getPixel(bitmap.width/2,bitmap.height/2)==color
            }; return matches }
            waitFor("Artwork must load independently",10000) { isColor(Color.RED) }
            assertEquals(context.localized("Play"),description())
            click(R.id.widget_play)
            waitFor("Widget Play must actually start playback") { var result=false; main { result=player!!.isPlaying }; result }
            waitFor("Widget must show Pause") { description()==context.localized("Pause") }
            click(R.id.widget_next)
            waitFor("Widget Next must advance the service") { var result=false; main { result=player!!.currentMediaItem?.mediaId=="widget-qa-b" }; result }
            waitFor("A book must use its own title and artwork") { text(R.id.widget_title)=="Widget Beta" && isColor(Color.BLUE) }
            assertEquals(context.getString(R.string.widget_audiobook),text(R.id.widget_status))
            // Same item, new metadata: no transition/isPlaying event should be needed.
            main {
                val controller = player!!
                controller.seekTo(5000)
                val updated = (0 until controller.mediaItemCount).map { index ->
                    val old = controller.getMediaItemAt(index)
                    old.buildUpon().setMediaMetadata(old.mediaMetadata.buildUpon()
                        .setTitle(if (index == 1) "Widget Beta corrected" else "Widget Alpha").build()).build()
                }
                updated.forEachIndexed { index, item -> controller.replaceMediaItem(index, item) }
            }
            waitFor("Metadata-only edits must update the widget") { text(R.id.widget_title)=="Widget Beta corrected" }
            main {
                assertEquals("Batch metadata repair must retain the current chapter", "widget-qa-b", player!!.currentMediaItem?.mediaId)
                assertTrue("Batch metadata repair must retain playback position", player!!.currentPosition >= 5000)
                assertTrue("Batch metadata repair must keep playing", player!!.playWhenReady)
            }
            screenshot(view!!,"widget-expanded.png")
            click(R.id.widget_play)
            waitFor("Pause must reach the service and widget") { var paused=false; main { paused=!player!!.playWhenReady }; paused && description()==context.localized("Play") }
            main { player!!.seekTo(0,0) }
            waitFor("Back to the first item") { text(R.id.widget_title)=="Widget Alpha" }
            repeat(3) { click(R.id.widget_play); Thread.sleep(120) }
            waitFor("Rapid toggles must preserve click order") { var active=false; main { active=player!!.playWhenReady }; active && description()==context.localized("Pause") }
            click(R.id.widget_play)
            waitFor("Pause before cold restart") { var paused=false; main { paused=!player!!.playWhenReady }; paused }
            main { player!!.seekTo(6300) }
            waitFor("Cold-resume position must be persisted") { context.getSharedPreferences("last_playback",0).getLong("position",0)==6300L }
            // Release the notification's playback controller as well as this test's
            // controller before requesting service destruction; keep queue/position.
            main { player!!.stop(); player!!.release(); player=null }
            context.stopService(Intent(context,PlaybackService::class.java))
            Thread.sleep(500)
            // Android's System UI may keep a paused session bound. Such a service is
            // legitimately alive; test passive pause/resume in either lifecycle state.
            val wasRunning = running()
            println("Widget resume: service retained by Android=$wasRunning")
            context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(ComponentName(context,PlayerWidget::class.java))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,intArrayOf(widgetId)))
            Thread.sleep(600)
            if (!wasRunning) assertFalse("Passive refresh must not start an audio service",running())
            assertEquals(context.localized("Play"),description())
            click(R.id.widget_play)
            waitFor("Widget must reconnect to a stopped service",12000) { running() && description()==context.localized("Pause") }
            player=connect()
            waitFor("Cold widget resume must produce real playback") { var active=false; main { active=player!!.isPlaying }; active }
            main { assertEquals("widget-qa-a",player!!.currentMediaItem!!.mediaId); assertTrue(player!!.currentPosition in 6300..16000); player!!.pause() }
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,76)
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,76)
            manager.updateAppWidgetOptions(widgetId,options)
            scenario!!.onActivity { activity -> view!!.layoutParams = (view!!.layoutParams as FrameLayout.LayoutParams).apply { height=(76*activity.resources.displayMetrics.density).toInt() } }
            waitFor("Resizing must select compact RemoteViews") { var compact=false; main { compact=view!!.findViewById<android.view.View>(R.id.widget_progress)==null }; compact }
            assertEquals("Widget Alpha",text(R.id.widget_title))
            screenshot(view!!,"widget-compact.png")
        } finally {
            if (player==null) player=runCatching { connect() }.getOrNull()
            main { player?.let { controller ->
                controller.pause()
                if (queue.isEmpty()) controller.clearMediaItems() else {
                    controller.setMediaItems(queue,index,position); controller.shuffleModeEnabled=shuffle; controller.repeatMode=repeat
                    controller.prepare(); controller.playWhenReady=playing
                }
                controller.release()
            }
                host?.stopListening(); host?.deleteHost()
            }
            scenario?.close()
            prefs.edit().putString("queue_mode",oldMode).putLong("sleep_until",oldSleep)
                .putLong("sleep_chapter",oldSleepChapter).putString("sleep_book",oldSleepBook).commit()
            bookPrefs.edit().apply { originalBookmarks.forEach { (key,value)-> if(value==null)remove(key)else putString(key,value) } }.commit()
            val restoredId=queue.getOrNull(index)?.mediaId?.toLongOrNull()
            if(restoredId!=null) MusicDb.get(context).openHelper.writableDatabase.execSQL("DELETE FROM plays WHERE trackId = ? AND playedAt >= ?",arrayOf(restoredId,started))
            files.forEach { it.delete() }
        }
    }
    private fun waitFor(message: String, timeout: Long = 8000, condition: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(!condition() && SystemClock.uptimeMillis()<end) Thread.sleep(60)
        assertTrue(message,condition())
    }
    private fun screenshot(view: AppWidgetHostView,name: String) {
        Thread.sleep(300)
        main {
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    private fun wav(): ByteArray {
        val size=8000*30*2
        return ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+size);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16);put("data".toByteArray());putInt(size)
        }.array()
    }
}
