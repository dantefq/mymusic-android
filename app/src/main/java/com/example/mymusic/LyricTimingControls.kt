package com.example.mymusic

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable fun LyricTimingControls(trackId: Long?) {
    val prefs = LocalContext.current.getSharedPreferences("lyrics_timing", Context.MODE_PRIVATE)
    var lead by remember(trackId) { mutableLongStateOf(prefs.getLong("$trackId", 0L)) }
    fun adjust(delta: Long) {
        lead = (lead + delta).coerceIn(-10_000L, 10_000L)
        prefs.edit().putLong("$trackId", lead).apply()
    }
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l("Lyrics timing"), color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(String.format(Locale.getDefault(), "%+.2f s", lead / 1000f), color = white, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { adjust(250) }, enabled = trackId != null) { Text(l("Earlier")) }
            TextButton(onClick = { lead = 0; prefs.edit().remove("$trackId").apply() }) { Text(l("Reset")) }
            TextButton(onClick = { adjust(-250) }, enabled = trackId != null) { Text(l("Later")) }
        }
    }
}
