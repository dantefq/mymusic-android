package com.example.mymusic

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** A bounded, mono/stereo 16/24-bit FLAC encoder and verifier (fixed predictor or verbatim). */
object FlacCodec {
    data class PcmSpec(val sampleRate: Int, val channels: Int, val bits: Int) {
        init {
            require(sampleRate in 1..655350 && channels in 1..2 && bits in listOf(16, 24))
        }
        val bytesPerFrame get() = channels * bits / 8
    }

    fun encodeAndVerify(pcm: File, flac: File, spec: PcmSpec): Long {
        require(pcm.length() % spec.bytesPerFrame == 0L) { "Incomplete PCM sample" }
        val total = pcm.length() / spec.bytesPerFrame
        require(total < (1L shl 36)) { "FLAC sample count exceeded" }
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(pcm).use { input ->
            RandomAccessFile(flac, "rw").use { out ->
                out.setLength(0)
                out.write("fLaC".toByteArray())
                out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
                out.write(ByteArray(34)) // patched after PCM is hashed
                var frameNo = 0L
                val block = ByteArray(4096 * spec.bytesPerFrame)
                while (true) {
                    var count = 0
                    while (count < block.size) {
                        val n = input.read(block, count, block.size - count)
                        if (n < 0) break
                        count += n
                    }
                    if (count == 0) break
                    require(count % spec.bytesPerFrame == 0) { "Short PCM block" }
                    digest.update(block, 0, count)
                    val samples = count / spec.bytesPerFrame
                    val channels = Array(spec.channels) { IntArray(samples) }
                    var p = 0
                    for (i in 0 until samples) for (ch in 0 until spec.channels) {
                        var raw = 0
                        repeat(spec.bits / 8) { j -> raw = raw or ((block[p++].toInt() and 255) shl (8 * j)) }
                        channels[ch][i] = (raw shl (32 - spec.bits)) shr (32 - spec.bits)
                    }
                    out.write(frame(channels, spec, frameNo++))
                }
                out.seek(8)
                val info = Bits()
                info.write(4096, 16); info.write(4096, 16)
                info.write(0, 24); info.write(0, 24)
                info.write(spec.sampleRate.toLong(), 20)
                info.write((spec.channels - 1).toLong(), 3)
                info.write((spec.bits - 1).toLong(), 5)
                info.write(total, 36)
                out.write(info.bytes())
                out.write(digest.digest())
            }
        }
        verify(pcm, flac, spec, total)
        return total
    }

    private fun frame(channels: Array<IntArray>, spec: PcmSpec, number: Long): ByteArray {
        val header = Bits()
        header.write(0x3ffe, 14); header.write(0, 1); header.write(0, 1)
        header.write(7, 4); header.write(0, 4)
        header.write(spec.channels - 1L, 4); header.write(0, 3); header.write(0, 1)
        for (b in utf8Number(number)) header.write(b.toInt() and 255, 8)
        header.write(channels[0].size - 1L, 16)
        val head = header.bytes()
        val body = Bits()
        for (samples in channels) writeSubframe(body, samples, spec.bits)
        body.pad()
        val raw = head + crc8(head).toByte() + body.bytes()
        val crc = crc16(raw)
        return raw + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
    }

    private fun writeSubframe(out: Bits, s: IntArray, bits: Int) {
        val residual = IntArray(maxOf(0, s.size - 1)) { s[it + 1] - s[it] }
        var bestK = 0
        var bestCost = Long.MAX_VALUE
        for (k in 0..14) {
            var cost = 8L + bits + 6 // subframe, warmup, residual header
            for (v in residual) {
                val u = zigzag(v)
                cost += (u ushr k) + 1L + k
            }
            if (cost < bestCost) { bestCost = cost; bestK = k }
        }
        if (bestCost >= 8L + s.size.toLong() * bits) {
            out.write(0x02, 8) // verbatim subframe
            for (v in s) out.write(v.toLong() and mask(bits), bits)
        } else {
            out.write(0x12, 8) // fixed predictor, order 1
            out.write(s[0].toLong() and mask(bits), bits)
            out.write(0, 2); out.write(0, 4); out.write(bestK, 4)
            for (v in residual) {
                val u = zigzag(v)
                val q = u ushr bestK
                repeat(q.toInt()) { out.write(0, 1) }
                out.write(1, 1)
                out.write(u and mask(bestK), bestK)
            }
        }
    }

    private fun verify(pcm: File, flac: File, spec: PcmSpec, expectedSamples: Long) {
        val expected = MessageDigest.getInstance("SHA-256")
        FileInputStream(pcm).use { stream ->
            val buf = ByteArray(65536)
            while (true) { val n = stream.read(buf); if (n < 0) break; expected.update(buf, 0, n) }
        }
        val actual = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(flac, "r").use { input ->
            require(ByteArray(4).also { input.readFully(it) }.contentEquals("fLaC".toByteArray()))
            input.seek(42)
            var count = 0L
            while (input.filePointer < input.length()) {
                val start = input.filePointer
                val a = input.readUnsignedByte(); val b = input.readUnsignedByte()
                require(a == 255 && b == 248) { "FLAC frame sync failed" }
                val blockCode = input.readUnsignedByte() ushr 4
                require(blockCode == 7)
                val fourth = input.readUnsignedByte()
                require((fourth ushr 4) == spec.channels - 1)
                val first = input.readUnsignedByte()
                val utfBytes = when {
                    first and 128 == 0 -> 1
                    first and 224 == 192 -> 2
                    first and 240 == 224 -> 3
                    first and 248 == 240 -> 4
                    first and 252 == 248 -> 5
                    else -> error("Invalid FLAC frame number")
                }
                repeat(utfBytes - 1) { input.readUnsignedByte() }
                val n = input.readUnsignedShort() + 1
                val headEnd = input.filePointer
                val stored8 = input.readUnsignedByte()
                input.seek(start)
                val header = ByteArray((headEnd - start).toInt()).also { input.readFully(it) }
                require(crc8(header) == stored8) { "FLAC header CRC mismatch" }
                input.readUnsignedByte()
                val reader = Reader(input)
                val samples = Array(spec.channels) { IntArray(n) }
                for (ch in 0 until spec.channels) {
                    require(reader.read(1) == 0L)
                    val type = reader.read(6).toInt()
                    require(reader.read(1) == 0L)
                    when (type) {
                        1 -> for (i in 0 until n) samples[ch][i] = sign(reader.read(spec.bits), spec.bits)
                        9 -> {
                            samples[ch][0] = sign(reader.read(spec.bits), spec.bits)
                            require(reader.read(2) == 0L && reader.read(4) == 0L)
                            val k = reader.read(4).toInt()
                            for (i in 1 until n) {
                                var q = 0L
                                while (reader.read(1) == 0L) { q++; require(q < 1_000_000) }
                                val u = (q shl k) or reader.read(k)
                                val delta = ((u ushr 1) xor -(u and 1)).toInt()
                                samples[ch][i] = samples[ch][i - 1] + delta
                            }
                        }
                        else -> error("Unexpected FLAC subframe")
                    }
                }
                reader.align()
                val end = input.filePointer
                val crcStored = input.readUnsignedShort()
                input.seek(start)
                val frame = ByteArray((end - start).toInt()).also { input.readFully(it) }
                require(crc16(frame) == crcStored) { "FLAC frame CRC mismatch" }
                input.skipBytes(2)
                for (i in 0 until n) for (ch in 0 until spec.channels) {
                    val v = samples[ch][i]
                    repeat(spec.bits / 8) { j -> actual.update((v ushr (j * 8)).toByte()) }
                }
                count += n
            }
            require(count == expectedSamples && expected.digest().contentEquals(actual.digest())) {
                "FLAC PCM differs from source"
            }
        }
    }

    private fun zigzag(v: Int): Long = ((v.toLong() shl 1) xor (v.toLong() shr 63)) and 0xffffffffL
    private fun mask(bits: Int): Long = if (bits == 0) 0 else (1L shl bits) - 1
    private fun sign(v: Long, bits: Int): Int = ((v shl (64 - bits)) shr (64 - bits)).toInt()
    private fun utf8Number(v: Long): ByteArray {
        if (v < 128) return byteArrayOf(v.toByte())
        val n = when { v < 1L shl 11 -> 2; v < 1L shl 16 -> 3; v < 1L shl 21 -> 4; else -> 5 }
        val out = ByteArray(n)
        for (i in n - 1 downTo 1) out[i] = (0x80 or ((v ushr (6 * (n - 1 - i))) and 63).toInt()).toByte()
        out[0] = (((0xff shl (8 - n)) and 255) or ((v ushr (6 * (n - 1))) and (0x7f ushr n).toLong()).toInt()).toByte()
        return out
    }
    private fun crc8(bytes: ByteArray): Int {
        var c = 0
        for (b in bytes) { c = c xor (b.toInt() and 255); repeat(8) { c = if (c and 128 != 0) ((c shl 1) xor 7) and 255 else (c shl 1) and 255 } }
        return c
    }
    private fun crc16(bytes: ByteArray): Int {
        var c = 0
        for (b in bytes) { c = c xor ((b.toInt() and 255) shl 8); repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xffff else (c shl 1) and 0xffff } }
        return c
    }
    private class Bits {
        private val out = java.io.ByteArrayOutputStream()
        private var byte = 0
        private var used = 0
        fun write(value: Int, count: Int) = write(value.toLong(), count)
        fun write(value: Long, count: Int) {
            for (i in count - 1 downTo 0) {
                byte = (byte shl 1) or ((value ushr i) and 1).toInt()
                if (++used == 8) { out.write(byte); byte = 0; used = 0 }
            }
        }
        fun pad() { if (used != 0) { out.write(byte shl (8 - used)); byte = 0; used = 0 } }
        fun bytes(): ByteArray { pad(); return out.toByteArray() }
    }
    private class Reader(private val file: RandomAccessFile) {
        private var byte = 0
        private var left = 0
        fun read(bits: Int): Long {
            var v = 0L
            repeat(bits) {
                if (left == 0) { byte = file.readUnsignedByte(); left = 8 }
                v = (v shl 1) or ((byte ushr --left) and 1).toLong()
            }
            return v
        }
        fun align() { left = 0 }
    }
}
