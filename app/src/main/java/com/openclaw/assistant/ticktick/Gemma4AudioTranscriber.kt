package com.openclaw.assistant.ticktick

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Gemma4TranscriptionResult(
    val text: String,
    val rawOutput: String,
    val backendUsed: String,
    val loadMs: Long,
    val inferMs: Long,
    val chunkCount: Int,
    val audioDurationMs: Long,
    val benchmarkSummary: String = "",
)

/**
 * On-demand LiteRT-LM Gemma 4 E2B English-only native audio transcriber.
 * Defaults to tuned CPU(6) inference with greedy decoding, audio preprocessing (silence trim
 * + soft AGC), pre-warmed English dictation KV-cache on mic start, and 90s idle auto-unload.
 */
object Gemma4AudioTranscriber {

    private const val TAG = "Gemma4Audio"
    private const val DEFAULT_BACKEND = "CPU"
    private const val IDLE_UNLOAD_TIMEOUT_MS = 90_000L
    private const val MAX_NUM_TOKENS = 1024
    private const val MAX_OUTPUT_TOKENS = 128
    private const val CHUNK_TIMEOUT_MS = 60_000L

    private val engineMutex = Mutex()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeEngine: Engine? = null

    @Volatile
    private var activeBackendName: String = ""

    @Volatile
    private var baseConversation: Conversation? = null

    @Volatile
    private var baseConversationBackend: String = ""

    @Volatile
    private var cloneUnsupportedForEngine: Boolean = false

    @Volatile
    private var idleUnloadJob: Job? = null

    private fun preferredCpuThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(4, 6)

    private fun deleteStaleGpuCaches(cacheDir: File) {
        val deleted = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.contains("mldrift_") }
            ?.count { it.delete() }
            ?: 0
        if (deleted > 0) {
            Log.i(TAG, "Removed $deleted stale mldrift_* GPU cache file(s) from ${cacheDir.name}")
        }
    }

    fun isLoaded(): Boolean = activeEngine?.isInitialized() == true

    fun unloadNow() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        lifecycleScope.launch {
            unloadAndAwait()
        }
    }

    suspend fun unloadAndAwait() = withContext(Dispatchers.IO) {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        engineMutex.withLock {
            closeEngineLocked()
        }
    }

    suspend fun prewarm(
        context: Context,
        @Suppress("UNUSED_PARAMETER") customVocabularyRaw: String = TranscriptNormalizer.DEFAULT_RAW_VOCABULARY,
        @Suppress("UNUSED_PARAMETER") contextIndex: ContextIndex? = null,
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (!Gemma4ModelManager.isModelReady(appContext)) return@withContext
        val modelFile = Gemma4ModelManager.modelFile(appContext)
        val cacheDir = Gemma4ModelManager.cacheDir(appContext)

        engineMutex.withLock {
            idleUnloadJob?.cancel()
            idleUnloadJob = null
            try {
                ensureEngineInitializedLocked(
                    modelFile = modelFile,
                    cacheDir = cacheDir,
                    backendName = DEFAULT_BACKEND,
                    reclaimGpuCache = true,
                )
                val engine = activeEngine ?: return@withLock
                if (!cloneUnsupportedForEngine) {
                    ensureBaseConversationLocked(engine, DEFAULT_BACKEND)
                }
            } finally {
                if (activeEngine != null) {
                    scheduleIdleUnloadLocked()
                }
            }
        }
    }

    suspend fun transcribePcm(
        context: Context,
        pcmBytes: ByteArray,
        @Suppress("UNUSED_PARAMETER") customVocabularyRaw: String = TranscriptNormalizer.DEFAULT_RAW_VOCABULARY,
        @Suppress("UNUSED_PARAMETER") contextIndex: ContextIndex? = null,
        backendOverride: String? = null,
    ): Gemma4TranscriptionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val modelFile = Gemma4ModelManager.modelFile(appContext)
        check(Gemma4ModelManager.isModelReady(appContext)) {
            "Gemma 4 model missing or incomplete at ${modelFile.absolutePath}"
        }
        val cacheDir = Gemma4ModelManager.cacheDir(appContext)
        val cleanedPcm = AudioPcmWavHelper.preprocessForAsr(pcmBytes)
        val audioDurationMs = AudioPcmWavHelper.durationMs(cleanedPcm)
        val chunks = AudioPcmWavHelper.splitPcmIntoChunks(cleanedPcm)
        check(chunks.isNotEmpty()) { "Empty audio buffer" }

        val forcedBackend = backendOverride?.trim()?.lowercase(Locale.ROOT)?.let {
            when (it) {
                "gpu" -> "GPU"
                "cpu" -> "CPU"
                else -> null
            }
        }
        val backendName = forcedBackend ?: DEFAULT_BACKEND
        val reclaimGpuCache = (forcedBackend == null && backendName == "CPU")

        engineMutex.withLock {
            idleUnloadJob?.cancel()
            idleUnloadJob = null

            try {
                runWithBackendLocked(
                    modelFile = modelFile,
                    cacheDir = cacheDir,
                    backendName = backendName,
                    chunks = chunks,
                    audioDurationMs = audioDurationMs,
                    reclaimGpuCache = reclaimGpuCache,
                )
            } finally {
                if (activeEngine != null) {
                    scheduleIdleUnloadLocked()
                }
            }
        }
    }

    private fun ensureEngineInitializedLocked(
        modelFile: File,
        cacheDir: File,
        backendName: String,
        reclaimGpuCache: Boolean,
    ): Long {
        if (reclaimGpuCache && backendName == "CPU") {
            deleteStaleGpuCaches(cacheDir)
        }
        if (activeEngine != null && activeEngine?.isInitialized() == true && activeBackendName == backendName) {
            return 0L
        }
        closeEngineLocked()
        val cpuThreads = preferredCpuThreads()
        val loadStart = SystemClock.elapsedRealtime()
        val backend: Backend = if (backendName == "CPU") {
            Backend.CPU(threadCount = cpuThreads)
        } else {
            Backend.GPU()
        }
        val engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                visionBackend = null,
                audioBackend = Backend.CPU(threadCount = cpuThreads),
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = cacheDir.absolutePath,
            ),
        )
        try {
            engine.initialize()
        } catch (t: Throwable) {
            runCatching { engine.close() }
            throw t
        }
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        activeEngine = engine
        activeBackendName = backendName
        Log.i(TAG, "Initialized Gemma 4 E2B backend=$backendName threads=$cpuThreads in ${loadMs}ms")
        return loadMs
    }

    private fun buildConversationConfig(prefillPrefaceOnInit: Boolean): ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of(Gemma4PromptBuilder.buildEnglishSystemInstruction()),
        samplerConfig = SamplerConfig(
            topK = 1,
            topP = 1.0,
            temperature = 0.0,
        ),
        prefillPrefaceOnInit = prefillPrefaceOnInit,
        maxOutputToken = MAX_OUTPUT_TOKENS,
        thinkingConfig = ThinkingConfig(enableThinking = false),
    )

    private fun ensureBaseConversationLocked(
        engine: Engine,
        backendName: String,
    ): Conversation? {
        if (cloneUnsupportedForEngine) return null
        val currentBase = baseConversation
        if (currentBase != null && currentBase.isAlive && baseConversationBackend == backendName) {
            return currentBase
        }
        runCatching { currentBase?.close() }
        baseConversation = null
        val createdBase = runCatching {
            engine.createConversation(buildConversationConfig(prefillPrefaceOnInit = true))
        }.getOrNull()
        if (createdBase != null) {
            baseConversation = createdBase
            baseConversationBackend = backendName
        } else {
            cloneUnsupportedForEngine = true
        }
        return baseConversation
    }

    private fun borrowChunkConversationLocked(
        engine: Engine,
        backendName: String,
    ): Conversation {
        val base = ensureBaseConversationLocked(engine, backendName)
        if (base != null) {
            try {
                return base.clone()
            } catch (cloneErr: Throwable) {
                Log.w(TAG, "Conversation.clone() unsupported on $backendName (${cloneErr.message}); disabling clone cache", cloneErr)
                runCatching { base.close() }
                baseConversation = null
                baseConversationBackend = ""
                cloneUnsupportedForEngine = true
            }
        }
        return engine.createConversation(buildConversationConfig(prefillPrefaceOnInit = false))
    }

    private suspend fun runWithBackendLocked(
        modelFile: File,
        cacheDir: File,
        backendName: String,
        chunks: List<ByteArray>,
        audioDurationMs: Long,
        reclaimGpuCache: Boolean,
    ): Gemma4TranscriptionResult {
        val loadMs = ensureEngineInitializedLocked(
            modelFile = modelFile,
            cacheDir = cacheDir,
            backendName = backendName,
            reclaimGpuCache = reclaimGpuCache,
        )
        val engine = checkNotNull(activeEngine) { "LiteRT-LM engine unavailable" }
        val inferStart = SystemClock.elapsedRealtime()
        val rawChunkOutputs = mutableListOf<String>()
        val cleanChunkOutputs = mutableListOf<String>()
        var lastBenchSummary = ""

        for (chunkPcm in chunks) {
            val wavBytes = AudioPcmWavHelper.pcmToWav(chunkPcm)
            val (rawOut, benchSummary) = inferSingleChunk(engine, backendName, wavBytes)
            rawChunkOutputs += rawOut
            if (benchSummary.isNotEmpty()) {
                lastBenchSummary = benchSummary
            }
            val cleaned = Gemma4PromptBuilder.sanitizeModelOutput(rawOut)
            if (cleaned.isNotEmpty()) {
                cleanChunkOutputs += cleaned
            }
        }

        val inferMs = SystemClock.elapsedRealtime() - inferStart
        return Gemma4TranscriptionResult(
            text = cleanChunkOutputs.joinToString(" ").trim(),
            rawOutput = rawChunkOutputs.joinToString(" | "),
            backendUsed = backendName,
            loadMs = loadMs,
            inferMs = inferMs,
            chunkCount = chunks.size,
            audioDurationMs = audioDurationMs,
            benchmarkSummary = lastBenchSummary,
        )
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun inferSingleChunk(
        engine: Engine,
        backendName: String,
        wavBytes: ByteArray,
    ): Pair<String, String> {
        val conversation = borrowChunkConversationLocked(engine, backendName)
        return try {
            val inputContents = Contents.of(
                Content.AudioBytes(wavBytes),
                Content.Text("Transcribe this English voice task."),
            )
            val rawOut = runConversationMessage(
                conversation = conversation,
                contents = inputContents,
                timeoutMs = CHUNK_TIMEOUT_MS,
            )
            val benchSummary = runCatching {
                val b = conversation.getBenchmarkInfo()
                String.format(
                    Locale.US,
                    "ttft=%.2fs prefill=%d@%.1ftps decode=%d@%.1ftps",
                    b.timeToFirstTokenInSecond,
                    b.lastPrefillTokenCount,
                    b.lastPrefillTokensPerSecond,
                    b.lastDecodeTokenCount,
                    b.lastDecodeTokensPerSecond,
                )
            }.getOrNull().orEmpty()
            if (benchSummary.isNotEmpty()) {
                Log.i(TAG, "Gemma4 benchmark [$backendName]: $benchSummary")
            }
            rawOut to benchSummary
        } catch (t: Throwable) {
            runCatching { conversation.cancelProcess() }
            throw t
        } finally {
            runCatching { conversation.close() }
        }
    }

    private suspend fun runConversationMessage(
        conversation: Conversation,
        contents: Contents,
        timeoutMs: Long,
    ): String = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            val sb = StringBuilder()

            cont.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    runCatching { conversation.cancelProcess() }
                }
            }

            conversation.sendMessageAsync(
                contents = contents,
                callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        sb.append(message.toString())
                    }

                    override fun onDone() {
                        if (finished.compareAndSet(false, true)) {
                            cont.resume(sb.toString())
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        if (finished.compareAndSet(false, true)) {
                            cont.resumeWithException(throwable)
                        }
                    }
                },
                extraContext = mapOf("enable_thinking" to false),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
        }
    }

    private fun scheduleIdleUnloadLocked() {
        idleUnloadJob?.cancel()
        idleUnloadJob = lifecycleScope.launch {
            delay(IDLE_UNLOAD_TIMEOUT_MS)
            engineMutex.withLock {
                if (activeEngine != null) {
                    Log.i(TAG, "Auto-unloading Gemma 4 engine after ${IDLE_UNLOAD_TIMEOUT_MS}ms idle")
                    closeEngineLocked()
                }
            }
        }
    }

    private fun closeEngineLocked() {
        runCatching { baseConversation?.close() }
        baseConversation = null
        baseConversationBackend = ""
        cloneUnsupportedForEngine = false
        runCatching { activeEngine?.close() }
        activeEngine = null
        activeBackendName = ""
    }
}
