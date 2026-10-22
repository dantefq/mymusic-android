package com.example.mymusic

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

fun favoriteTrackIds(context: Context): Set<Long> = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    .getStringSet("tracks", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()

fun toggleFavoriteTrack(context: Context, id: Long) {
    val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    val ids = prefs.getStringSet("tracks", emptySet()).orEmpty().toMutableSet()
    if (!ids.remove(id.toString())) ids.add(id.toString())
    prefs.edit().putStringSet("tracks", ids).apply()
}

@Composable fun rememberFavoriteTracks(): Set<Long> {
    val context = LocalContext.current
    var ids by remember(context) { mutableStateOf(favoriteTrackIds(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "tracks") ids = favoriteTrackIds(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        ids = favoriteTrackIds(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return ids
}
