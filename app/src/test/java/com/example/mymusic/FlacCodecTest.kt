package com.example.mymusic

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class FlacCodecTest {
    @Test fun roundTrips16BitStereoWithPartialLastBlock() = check(FlacCodec.PcmSpec(44100, 2, 16), 5003)
    @Test fun roundTrips24BitMono() = check(FlacCodec.PcmSpec(48000, 1, 24), 8007)
    @Test fun compressesSilence() {
        val pcm = File.createTempFile("silence", ".pcm")
        val flac = File.createTempFile("silence", ".flac")
        try {
            pcm.writeBytes(ByteArray(44100 * 4))
            FlacCodec.encodeAndVerify(pcm, flac, FlacCodec.PcmSpec(44100, 2, 16))
            assertTrue(flac.length() < pcm.length() / 4)
        } finally { pcm.delete(); flac.delete() }
    }

    private fun check(spec: FlacCodec.PcmSpec, frames: Int) {
        val pcm = File.createTempFile("input", ".pcm")
        val flac = File.createTempFile("encoded", ".flac")
        try {
            val random = Random(99)
            pcm.outputStream().use { out ->
                repeat(frames) { index ->
                    repeat(spec.channels) {
                        val sample = if (index < frames / 2) index % 256 - 128 else random.nextInt()
                        repeat(spec.bits / 8) { byte -> out.write(sample ushr (byte * 8)) }
                    }
                }
            }
            assertTrue(FlacCodec.encodeAndVerify(pcm, flac, spec) == frames.toLong())
            assertTrue(flac.length() > 42)
        } finally { pcm.delete(); flac.delete() }
    }
}
