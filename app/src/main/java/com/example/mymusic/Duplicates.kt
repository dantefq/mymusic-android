package com.example.mymusic

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class DuplicateGroup(val hash: String, val tracks: List<Track>)
data class DuplicateReplacement(val remove: Track, val keep: Track)

/** Keeps the playing copy, then a playlist copy, then the oldest stable MediaStore entry. */
fun duplicateCleanupPlan(groups: List<DuplicateGroup>, activeId: Long?, playlistIds: Set<Long>): List<DuplicateReplacement> =
    groups.flatMap { group ->
        val ordered = group.tracks.distinctBy { it.id }.sortedWith(
            compareByDescending<Track> { it.id == activeId }.thenByDescending { it.id in playlistIds }
                .thenBy { it.addedAt }.thenBy { it.id })
        ordered.drop(1).map { DuplicateReplacement(it, ordered.first()) }
    }

object DuplicateScanner {
    suspend fun scan(context: Context, tracks: List<Track>): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val bySize = tracks.groupBy { track ->
            runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(track.uri), "r")?.use { it.length } }
                .getOrNull() ?: -1L
        }
        val candidates = bySize.values.filter { it.size > 1 }.flatten()
        candidates.mapNotNull { track ->
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(Uri.parse(track.uri))!!.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) } to track
            }.getOrNull()
        }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
            .map { DuplicateGroup(it.key, it.value) }
    }
}

@Composable fun DuplicatesScreen(groups: List<DuplicateGroup>?, onScan: () -> Unit,
    onDelete: (Track) -> Unit, onCleanAll: () -> Unit, autoDelete: Boolean, onAutoDelete: (Boolean) -> Unit,
    scanning: Boolean, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 25.dp)) {
        item {
            CollectionHeader(l("Duplicates"), l("Exact SHA-256 matches. Review before deleting."))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(l("Auto-delete duplicates"), color = white); Text(l("Keep one verified copy. Android asks before deleting files."), color = muted, fontSize = 12.sp) }
                Switch(autoDelete, onCheckedChange = onAutoDelete)
            }
            Button(onClick = onScan, enabled = !scanning, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(if (groups == null) l("Scan files") else l("Scan again"))
            }
            if (!groups.isNullOrEmpty()) ConceptButton(l("Remove all duplicates"), onCleanAll, Modifier.padding(horizontal = 24.dp).fillMaxWidth(), enabled = !scanning)
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.height(22.dp))
            if (groups != null && groups.isEmpty()) Text(l("No exact duplicates found"),
                color = muted, modifier = Modifier.padding(horizontal = 24.dp))
        }
        items(groups.orEmpty()) { group ->
            Text(stringResource(R.string.identical_files, group.tracks.size), color = accent, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp))
            group.tracks.forEachIndexed { index, track ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 7.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = white, maxLines = 1)
                        Text(track.artist, color = muted, fontSize = 12.sp)
                    }
                    if (index > 0) TextButton(onClick = { onDelete(track) }) { Text(l("Delete")) }
                    else Text(l("KEEP"), color = muted, fontSize = 11.sp)
                }
            }
        }
    }
}
