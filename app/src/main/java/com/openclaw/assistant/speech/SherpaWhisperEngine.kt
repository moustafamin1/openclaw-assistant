package com.openclaw.assistant.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Thread-safe Sherpa-ONNX Whisper base.en (int8) recognizer loaded directly from Android assets.
 * Decodes 16kHz PCM audio completely offline with zero network or Google Speech IPC dependencies.
 */
object SherpaWhisperEngine {
    private const val TAG = "SherpaWhisperEngine"
    private const val MODEL_DIR = "whisper-base-en"
    private const val SAMPLE_RATE = 16000
    private const val MIN_SAMPLES = 4000 // 250ms
    private const val MIN_RMS_ENERGY = 0.003f

    private val loadMutex = Mutex()
    @Volatile
    private var recognizer: OfflineRecognizer? = null

    suspend fun getOrInit(context: Context): OfflineRecognizer? {
        recognizer?.let { return it }
        return loadMutex.withLock {
            recognizer?.let { return it }
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                runCatching {
                    val feat = FeatureConfig().apply {
                        sampleRate = SAMPLE_RATE
                        featureDim = 80
                    }
                    val whisper = OfflineWhisperModelConfig().apply {
                        encoder = "$MODEL_DIR/base.en-encoder.int8.onnx"
                        decoder = "$MODEL_DIR/base.en-decoder.int8.onnx"
                        language = "en"
                        task = "transcribe"
                    }
                    val model = OfflineModelConfig().apply {
                        this.whisper = whisper
                        tokens = "$MODEL_DIR/base.en-tokens.txt"
                        modelType = "whisper"
                        numThreads = 4
                        provider = "cpu"
                        debug = false
                    }
                    val config = OfflineRecognizerConfig().apply {
                        featConfig = feat
                        modelConfig = model
                        decodingMethod = "greedy_search"
                    }
                    OfflineRecognizer(assetManager = appContext.assets, config = config)
                }.onSuccess {
                    recognizer = it
                }.onFailure { err ->
                    Log.e(TAG, "Failed to initialize Sherpa-ONNX Whisper", err)
                }.getOrNull()
            }
        }
    }

    suspend fun transcribePcm16(
        context: Context,
        pcmChunks: Collection<ByteArray>,
    ): String = withContext(Dispatchers.IO) {
        val samples = pcm16ChunksToFloat(pcmChunks)
        if (!hasSpeechEnergy(samples)) {
            Log.d(TAG, "Clip rejected by silence/duration guard (samples=${samples.size})")
            return@withContext ""
        }

        val engine = getOrInit(context) ?: return@withContext ""
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            val text = engine.getResult(stream).text.trim()
            if (isLikelySilenceHallucination(text)) "" else text
        } catch (e: Exception) {
            Log.e(TAG, "Whisper decode failed", e)
            ""
        } finally {
            stream.release()
        }
    }

    internal fun pcm16ChunksToFloat(chunks: Collection<ByteArray>): FloatArray {
        val totalBytes = chunks.sumOf { it.size }
        val totalSamples = totalBytes / 2
        if (totalSamples <= 0) return FloatArray(0)

        val out = FloatArray(totalSamples)
        var outIdx = 0
        for (chunk in chunks) {
            val usableBytes = chunk.size - (chunk.size % 2)
            val bb = ByteBuffer.wrap(chunk, 0, usableBytes).order(ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 2 && outIdx < totalSamples) {
                out[outIdx++] = bb.short / 32768.0f
            }
        }
        return out
    }

    internal fun hasSpeechEnergy(samples: FloatArray): Boolean {
        if (samples.size < MIN_SAMPLES) return false
        var sumSq = 0.0
        for (s in samples) {
            sumSq += (s * s).toDouble()
        }
        val rms = sqrt(sumSq / samples.size.toDouble()).toFloat()
        return rms >= MIN_RMS_ENERGY
    }

    private fun isLikelySilenceHallucination(text: String): Boolean {
        val cleaned = text.trim().lowercase()
        return cleaned.isEmpty() ||
            cleaned == "[blank_audio]" ||
            cleaned == "(blank audio)" ||
            cleaned == "you" ||
            cleaned == "thank you."
    }
}
