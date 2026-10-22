package com.example.mymusic

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Debug-only real RemoteViews host. It deliberately never connects to the playback service. */
class WidgetHarnessActivity : Activity() {
    lateinit var root: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0F171E.toInt()) }
        setContentView(root)
    }
}
