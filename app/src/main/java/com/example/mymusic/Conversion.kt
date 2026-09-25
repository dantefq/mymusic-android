package com.example.mymusic

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class Conversion(private val context: Context) {
    suspend fun toFlac(track: Track): Uri = withContext(Dispatchers.IO) {
        val pcm = File.createTempFile("pcm-", ".raw", context.cacheDir)
        val flac = File.createTempFile("flac-", ".flac", context.cacheDir)
        try {
            val source = Uri.parse(track.uri)
            val spec = if (track.mime.contains("wav", true) || track.uri.endsWith(".wav", true))
                readWav(source, pcm) else decodeAlac(source, pcm)
            require(pcm.length() > 0) { "No PCM decoded" }
            FlacCodec.encodeAndVerify(pcm, flac, spec)
            publish(track, flac)
        } finally { pcm.delete(); flac.delete() }
    }

    private fun readWav(uri: Uri, destination: File): FlacCodec.PcmSpec {
        context.contentResolver.openInputStream(uri)!!.use { source ->
            val input = DataInputStream(source)
            require(readFour(input) == "RIFF") { "Only RIFF WAV is supported" }
            read32(input)
            require(readFour(input) == "WAVE")
            var spec: FlacCodec.PcmSpec? = null
            while (true) {
                val tag = runCatching { readFour(input) }.getOrNull() ?: break
                val size = read32(input)
                require(size in 0..Int.MAX_VALUE.toLong()) { "WAV chunk too large" }
                when (tag) {
                    "fmt " -> {
                        require(size >= 16)
                        val codec = read16(input)
                        val channels = read16(input)
                        val sampleRate = read32(input).toInt()
                        read32(input) // byte rate
                        val align = read16(input)
                        val bits = read16(input)
                        if (codec == 0xfffe) {
                            require(size >= 40) { "Invalid extensible WAV format" }
                            require(read16(input) >= 22)
                            val validBits = read16(input)
                            require(validBits == bits) { "WAV valid bits differ from container bits" }
                            read32(input) // channel mask
                            val pcmGuid = ByteArray(16).also(input::readFully)
                            require(pcmGuid.contentEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0,
                                0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71))) {
                                "Only integer PCM WAV is supported"
                            }
                        } else require(codec == 1) { "Only integer PCM WAV is supported" }
                        spec = FlacCodec.PcmSpec(sampleRate, channels, bits)
                        require(align == spec.bytesPerFrame)
                        skip(input, size - if (codec == 0xfffe) 40 else 16)
                    }
                    "data" -> {
                        val format = requireNotNull(spec) { "WAV fmt chunk must precede data" }
                        require(size % format.bytesPerFrame == 0L) { "Partial WAV sample" }
                        FileOutputStream(destination).use { output ->
                            var remaining = size
                            val bytes = ByteArray(65536)
                            while (remaining > 0) {
                                val n = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                                require(n > 0) { "Truncated WAV" }
                                output.write(bytes, 0, n)
                                remaining -= n
                            }
                        }
                        return format
                    }
                    else -> skip(input, size)
                }
                if (size and 1L != 0L) skip(input, 1)
            }
            error("WAV data chunk missing")
        }
    }

    private fun decodeAlac(uri: Uri, destination: File): FlacCodec.PcmSpec {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.contains("alac", true) == true
            } ?: error("File is not ALAC, or Android cannot extract its ALAC track")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceBits = if (format.containsKey("bits-per-sample"))
                format.getInteger("bits-per-sample") else 0
            if (sourceBits == 24) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_24BIT_PACKED)
            val codec = try { MediaCodec.createDecoderByType(mime) }
                catch (e: Exception) { error("No ALAC decoder installed on this device") }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var spec: FlacCodec.PcmSpec? = null
                FileOutputStream(destination).use { output ->
                    while (!outputDone) {
                        if (!inputDone) {
                            val id = codec.dequeueInputBuffer(10_000)
                            if (id >= 0) {
                                val buf = codec.getInputBuffer(id)!!
                                buf.clear()
                                val size = extractor.readSampleData(buf, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(id, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(id, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val id = codec.dequeueOutputBuffer(info, 10_000)
                        if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val decoded = codec.outputFormat
                            val encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val bits = when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> 16
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                                else -> error("ALAC decoder output is not lossless integer PCM")
                            }
                            require(sourceBits == 0 || bits == sourceBits) { "Decoder reduced ALAC bit depth" }
                            spec = FlacCodec.PcmSpec(decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT), bits)
                        } else if (id >= 0) {
                            if (info.size > 0) {
                                val bytes = ByteArray(info.size)
                                codec.getOutputBuffer(id)!!.apply { position(info.offset); limit(info.offset + info.size); get(bytes) }
                                output.write(bytes)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(id, false)
                        }
                    }
                }
                return requireNotNull(spec) { "ALAC decoder produced no format" }
            } finally { codec.stop(); codec.release() }
        } finally { extractor.release() }
    }

    private fun publish(track: Track, source: File): Uri {
        val base = track.title.replace(Regex("[\\/:*?\"<>|]"), "_").take(100)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$base.flac")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/MyMusic")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create FLAC in MediaStore")
        try {
            context.contentResolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
    }

    private fun readFour(input: DataInputStream): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
    private fun read16(input: DataInputStream): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    private fun read32(input: DataInputStream): Long = read16(input).toLong() or (read16(input).toLong() shl 16)
    private fun skip(input: InputStream, amount: Long) {
        var left = amount
        while (left > 0) {
            val n = input.skip(left)
            require(n > 0) { "Truncated WAV" }
            left -= n
        }
    }
}
