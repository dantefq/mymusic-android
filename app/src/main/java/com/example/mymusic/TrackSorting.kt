package com.example.mymusic

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

fun sortedTracks(tracks: List<Track>, field: String, descending: Boolean, lossless: Boolean): List<Track> {
    val comparator = when (field) {
        "Date added" -> compareBy<Track> { it.addedAt }
        "Artist" -> compareBy { it.artist.lowercase(Locale.ROOT) }
        "Duration" -> compareBy { it.durationMs }
        "Album" -> compareBy { it.album.lowercase(Locale.ROOT) }
        else -> compareBy { it.title.lowercase(Locale.ROOT) }
    }.thenBy { it.id }
    return tracks.filter { !lossless || it.isLossless() }.sortedWith(if (descending) comparator.reversed() else comparator)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SortSheet(field: String, descending: Boolean, lossless: Boolean, onDismiss: () -> Unit,
    onApply: (String, Boolean, Boolean) -> Unit) {
    var selected by remember { mutableStateOf(field) }
    var reversed by remember { mutableStateOf(descending) }
    var onlyLossless by remember { mutableStateOf(lossless) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(l("Sort and filter"), color = white, fontFamily = headingFont, fontSize = 28.sp)
            listOf("Name", "Date added", "Artist", "Duration", "Album").forEach { name ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected == name, onClick = { selected = name })
                    TextButton(onClick = { selected = name }) { Text(l(name), color = white) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(!reversed, onClick = { reversed = false }, label = { Text(l("Ascending")) })
                FilterChip(reversed, onClick = { reversed = true }, label = { Text(l("Descending")) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(!onlyLossless, onClick = { onlyLossless = false }, label = { Text(l("All audio")) })
                FilterChip(onlyLossless, onClick = { onlyLossless = true }, label = { Text(l("Lossless")) })
            }
            Button(onClick = { onApply(selected, reversed, onlyLossless) }, modifier = Modifier.fillMaxWidth()) { Text(l("Apply")) }
        }
    }
}
