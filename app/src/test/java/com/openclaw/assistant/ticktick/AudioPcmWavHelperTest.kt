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

    @Test
    fun preprocessForAsrTrimsDeadSilenceAndBoostsQuietSpeechWithoutClipping() {
        val leadSilence = ByteArray(AudioPcmWavHelper.BYTES_PER_SECOND * 2) // 2s silence
        val quietSpeech = generateTonePcm(800, amplitude = 1200)
        val tailSilence = ByteArray(AudioPcmWavHelper.BYTES_PER_SECOND * 2) // 2s silence
        val raw = leadSilence + quietSpeech + tailSilence // 4.8s total

        val cleaned = AudioPcmWavHelper.preprocessForAsr(raw)
        val cleanedDuration = AudioPcmWavHelper.durationMs(cleaned)

        // Should keep 800ms speech + ~440ms padding (~1.24s), trimming >3s of dead silence
        assertTrue("Expected trimmed duration < 1800ms, got $cleanedDuration", cleanedDuration in 900L..1800L)

        // Peak should be boosted well above 1200 without clipping above 29000
        var peak = 0
        var i = 0
        while (i + 1 < cleaned.size) {
            val sample = ((cleaned[i + 1].toInt() shl 8) or (cleaned[i].toInt() and 0xFF))
            val absSample = kotlin.math.abs(sample)
            if (absSample > peak) peak = absSample
            i += 2
        }
        assertTrue("Expected boosted peak > 4000, got $peak", peak > 4000)
        assertTrue("Expected no clipping <= 29000, got $peak", peak <= 29_000)
    }
}
