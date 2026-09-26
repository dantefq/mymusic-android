package com.example.mymusic

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONObject
import java.io.File

data class LocalTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artUri: String? = null,
    val lyrics: String? = null,
    val genre: String? = null
)

internal fun String?.tagValue(): String? = this?.trim()?.takeIf {
    it.isNotEmpty() && it != "<unknown>" && !it.equals("Unknown", true)
}

class LocalTagReader(private val context: Context) {
    fun read(uri: Uri, id: Long, path: String?): LocalTags {
        val sidecar = sidecar(path)
        val embedded = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val picture = retriever.embeddedPicture
                LocalTags(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).tagValue(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).tagValue(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).tagValue(),
                    picture?.takeIf { it.size <= 8_000_000 }?.let { cacheCover(id, it) },
                    null,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).tagValue()
                )
            } finally { retriever.release() }
        }.getOrDefault(LocalTags())
        return LocalTags(
            embedded.title ?: sidecar.title,
            embedded.artist ?: sidecar.artist,
            embedded.album ?: sidecar.album,
            embedded.artUri ?: sidecar.artUri,
            sidecar.lyrics,
            embedded.genre ?: sidecar.genre
        )
    }

    private fun sidecar(path: String?): LocalTags {
        val audio = path?.let(::File)?.takeIf { it.isFile && it.canRead() } ?: return LocalTags()
        val stem = audio.nameWithoutExtension
        val parent = audio.parentFile ?: return LocalTags()
        val json = listOf(File(parent, "$stem.json"), File(parent, "metadata.json"))
            .firstOrNull { it.isFile && it.canRead() && it.length() <= 65_536 }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val lyrics = File(parent, "$stem.lrc").takeIf { it.isFile && it.canRead() && it.length() <= 1_000_000 }
            ?.let { runCatching { it.readText() }.getOrNull() }
        val cover = listOf("$stem.jpg", "$stem.png", "cover.jpg", "cover.png", "folder.jpg")
            .map { File(parent, it) }.firstOrNull { it.isFile && it.canRead() }
        return LocalTags(
            json?.optString("title").tagValue(), json?.optString("artist").tagValue(),
            json?.optString("album").tagValue(), cover?.let { Uri.fromFile(it).toString() },
            lyrics.tagValue() ?: json?.optString("lyrics").tagValue(),
            json?.optString("genre").tagValue()
        )
    }

    private fun cacheCover(id: Long, bytes: ByteArray): String? = runCatching {
        val directory = File(context.filesDir, "covers").apply { mkdirs() }
        val target = File(directory, "$id.cover")
        if (!target.exists() || target.length() != bytes.size.toLong() || !target.readBytes().contentEquals(bytes)) {
            target.writeBytes(bytes)
        }
        Uri.fromFile(target).toString()
    }.getOrNull()
}
