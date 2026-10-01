package com.example.mymusic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
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
            Spacer(Modifier.height(12.dp))
            listOf("Name", "Date added", "Artist", "Duration", "Album").forEach { name ->
                Row(Modifier.fillMaxWidth().height(36.dp).clickable { selected = name }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == name, onClick = { selected = name })
                    Text(l(name), color = white, fontFamily = headingFont, fontSize = 17.sp)
                }
            }
            ConceptDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
            Text(l("Order"), color = white, fontFamily = headingFont, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            SegmentedChoices(listOf(l("Ascending"), l("Descending")), if (reversed) 1 else 0) { reversed = it == 1 }
            Spacer(Modifier.height(16.dp))
            Text(l("Audio quality"), color = white, fontFamily = headingFont, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            SegmentedChoices(listOf(l("All audio"), l("Lossless only")), if (onlyLossless) 1 else 0) { onlyLossless = it == 1 }
            Spacer(Modifier.height(18.dp))
            ConceptButton(l("Apply"), { onApply(selected, reversed, onlyLossless) }, Modifier.fillMaxWidth())
        }
    }
}
