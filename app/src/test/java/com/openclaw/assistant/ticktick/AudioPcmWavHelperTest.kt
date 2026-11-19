package com.openclaw.assistant.ticktick

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class AudioPcmWavHelperTest {

    private fun generateTonePcm(durationMs: Int, amplitude: Int = 6000): ByteArray {
        val sampleCount = (AudioPcmWavHelper.SAMPLE_RATE * durationMs) / 1000
        val out = ByteArray(sampleCount * 2)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val s = (sin(2.0 * Math.PI * 440.0 * i / AudioPcmWavHelper.SAMPLE_RATE) * amplitude).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf.putShort(s.toShort())
        }
        return out
    }

    @Test
    fun pcmToWavAndBackRoundTripsWithValidRiffHeader() {
        val pcm = generateTonePcm(500)
        val wav = AudioPcmWavHelper.pcmToWav(pcm)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        val headerBuf = ByteBuffer.wrap(wav, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, headerBuf.getInt(4))
        assertEquals(1.toShort(), headerBuf.getShort(20)) // PCM format
        assertEquals(1.toShort(), headerBuf.getShort(22)) // mono
        assertEquals(16_000, headerBuf.getInt(24)) // sample rate
        assertEquals(32_000, headerBuf.getInt(28)) // byte rate
        assertEquals(2.toShort(), headerBuf.getShort(32)) // block align
        assertEquals(16.toShort(), headerBuf.getShort(34)) // bits per sample
        assertEquals(pcm.size, headerBuf.getInt(40)) // data size

        assertArrayEquals(pcm, AudioPcmWavHelper.wavToPcm(wav))
    }

    @Test
    fun hasAudibleSpeechRejectsShortOrSilentAudioAndAcceptsSpeechLevelTone() {
        assertFalse(AudioPcmWavHelper.hasAudibleSpeech(ByteArray(3200))) // 100ms
        assertFalse(AudioPcmWavHelper.hasAudibleSpeech(ByteArray(32_000))) // 1s pure zeros
        assertFalse(AudioPcmWavHelper.hasAudibleSpeech(generateTonePcm(600, amplitude = 30)))
        assertTrue(AudioPcmWavHelper.hasAudibleSpeech(generateTonePcm(600, amplitude = 3000)))
    }

    @Test
    fun splitPcmIntoChunksCutsAtQuietestWindowWithinLimits() {
        val first25s = generateTonePcm(25_000, amplitude = 5000)
        val quietGap = ByteArray(AudioPcmWavHelper.BYTES_PER_SECOND) // 1s silence at 25s..26s
        val tail10s = generateTonePcm(10_000, amplitude = 5000)
        val full = first25s + quietGap + tail10s // 36s total

        val chunks = AudioPcmWavHelper.splitPcmIntoChunks(full, maxChunkSeconds = 28)
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].size in (24 * AudioPcmWavHelper.BYTES_PER_SECOND)..(28 * AudioPcmWavHelper.BYTES_PER_SECOND))
        assertEquals(full.size, chunks[0].size + chunks[1].size)
        // Cut point should land cleanly inside the silent gap (25.0s..26.0s)
        assertTrue(chunks[0].size in (25 * AudioPcmWavHelper.BYTES_PER_SECOND)..(26 * AudioPcmWavHelper.BYTES_PER_SECOND))
    }
}
