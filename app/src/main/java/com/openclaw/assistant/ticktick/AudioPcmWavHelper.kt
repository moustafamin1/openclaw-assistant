package com.openclaw.assistant.ticktick

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Pure-Kotlin PCM / WAV utilities for 16kHz 16-bit mono audio capture and
 * LiteRT-LM Gemma 4 E2B multimodal input (max 30s per audio encoder pass).
 */
object AudioPcmWavHelper {

    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8 // 2 bytes
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE // 32,000 bytes/sec
    const val MAX_CHUNK_SECONDS = 28
    const val MAX_TOTAL_SECONDS = 180
    private const val MIN_SPEECH_BYTES = BYTES_PER_SECOND / 5 // 200 ms = 6,400 bytes
    private const val MIN_PEAK_AMPLITUDE = 280
    private const val MIN_RMS_AMPLITUDE = 45.0
    private const val SPLIT_SEARCH_START_SEC = 24
    private const val SPLIT_WINDOW_MS = 100

    /**
     * Wraps 16kHz 16-bit mono little-endian raw PCM bytes in a standard 44-byte RIFF/WAVE header.
     */
    fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val pcmLength = pcmBytes.size
        val channels = CHANNELS
        val bitsPerSample = BITS_PER_SAMPLE
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val riffChunkSize = 36 + pcmLength

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(riffChunkSize)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16) // PCM subchunk1 size
            putShort(1.toShort()) // AudioFormat = 1 (PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(pcmLength)
        }.array()

        return header + pcmBytes
    }

    /**
     * Extracts raw PCM payload from a RIFF/WAVE container by locating the `data` subchunk
     * (falling back to skipping the standard 44-byte header).
     */
    fun wavToPcm(wavBytes: ByteArray): ByteArray {
        if (wavBytes.size <= 44) return ByteArray(0)
        val isRiff = wavBytes[0] == 'R'.code.toByte() &&
            wavBytes[1] == 'I'.code.toByte() &&
            wavBytes[2] == 'F'.code.toByte() &&
            wavBytes[3] == 'F'.code.toByte()
        if (!isRiff) return wavBytes

        var offset = 12
        while (offset + 8 <= wavBytes.size) {
            val id = String(wavBytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(wavBytes, offset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .coerceAtLeast(0)
            val payloadStart = offset + 8
            if (id == "data") {
                val payloadEnd = (payloadStart + chunkSize).coerceAtMost(wavBytes.size)
                return wavBytes.copyOfRange(payloadStart, payloadEnd)
            }
            val paddedSize = chunkSize + (chunkSize % 2)
            offset = payloadStart + paddedSize
        }

        return wavBytes.copyOfRange(44, wavBytes.size)
    }

    /**
     * Guards against empty button taps or silent pocket holds so we never invoke the LLM on noise.
     */
    fun hasAudibleSpeech(pcmBytes: ByteArray): Boolean {
        val alignedLen = pcmBytes.size - (pcmBytes.size % BYTES_PER_SAMPLE)
        if (alignedLen < MIN_SPEECH_BYTES) return false

        var peak = 0
        var sumSq = 0.0
        val sampleCount = alignedLen / BYTES_PER_SAMPLE
        var i = 0
        while (i < alignedLen) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val absSample = abs(sample)
            if (absSample > peak) peak = absSample
            sumSq += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val rms = kotlin.math.sqrt(sumSq / sampleCount.coerceAtLeast(1))
        return peak >= MIN_PEAK_AMPLITUDE && rms >= MIN_RMS_AMPLITUDE
    }

    /**
     * Splits PCM into segments of at most [maxChunkSeconds] (matching Gemma 4 E2B's 30s audio
     * window), choosing the quietest 100ms cut point near `[24s..maxChunkSeconds]`.
     */
    fun splitPcmIntoChunks(
        pcmBytes: ByteArray,
        maxChunkSeconds: Int = MAX_CHUNK_SECONDS,
    ): List<ByteArray> {
        val alignedLen = pcmBytes.size - (pcmBytes.size % BYTES_PER_SAMPLE)
        if (alignedLen <= 0) return emptyList()

        val maxChunkBytes = alignToSample(maxChunkSeconds * BYTES_PER_SECOND)
        if (alignedLen <= maxChunkBytes) {
            return listOf(pcmBytes.copyOf(alignedLen))
        }

        val chunks = mutableListOf<ByteArray>()
        var start = 0
        val searchStartOffset = alignToSample(SPLIT_SEARCH_START_SEC.coerceAtMost(maxChunkSeconds - 1) * BYTES_PER_SECOND)
        val windowBytes = alignToSample((SAMPLE_RATE * SPLIT_WINDOW_MS / 1000) * BYTES_PER_SAMPLE)

        while (start < alignedLen) {
            val remaining = alignedLen - start
            if (remaining <= maxChunkBytes) {
                chunks += pcmBytes.copyOfRange(start, alignedLen)
                break
            }

            val hardEnd = start + maxChunkBytes
            val searchStart = (start + searchStartOffset).coerceAtMost(hardEnd - windowBytes)
            var bestCut = hardEnd
            var lowestEnergy = Double.MAX_VALUE

            var pos = searchStart
            while (pos + windowBytes <= hardEnd) {
                val energy = windowMeanAbsEnergy(pcmBytes, pos, windowBytes)
                if (energy < lowestEnergy) {
                    lowestEnergy = energy
                    bestCut = alignToSample(pos + windowBytes / 2)
                }
                pos += windowBytes / 2
            }

            if (bestCut <= start) bestCut = hardEnd
            chunks += pcmBytes.copyOfRange(start, bestCut)
            start = bestCut
        }
        return chunks
    }

    fun durationMs(pcmBytes: ByteArray): Long {
        return (pcmBytes.size.toLong() * 1000L) / BYTES_PER_SECOND.toLong()
    }

    private fun alignToSample(byteCount: Int): Int = byteCount - (byteCount % BYTES_PER_SAMPLE)

    private fun windowMeanAbsEnergy(pcmBytes: ByteArray, start: Int, length: Int): Double {
        var total = 0.0
        var count = 0
        var i = start
        val end = (start + length).coerceAtMost(pcmBytes.size - 1)
        while (i < end) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            total += abs(sample).toDouble()
            count++
            i += 2
        }
        return if (count == 0) Double.MAX_VALUE else total / count
    }
}
