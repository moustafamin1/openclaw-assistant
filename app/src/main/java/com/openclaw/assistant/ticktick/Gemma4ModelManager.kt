package com.openclaw.assistant.ticktick

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class Gemma4DownloadState {
    object NotDownloaded : Gemma4DownloadState()
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : Gemma4DownloadState()
    data class Ready(val sizeBytes: Long) : Gemma4DownloadState()
    data class Error(val message: String) : Gemma4DownloadState()
}

/**
 * Manages local storage, resumable HTTPS download, and deletion for `gemma-4-E2B-it.litertlm`.
 */
object Gemma4ModelManager {

    private const val TAG = "Gemma4ModelManager"
    const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val MODEL_DOWNLOAD_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val EXPECTED_SIZE_BYTES = 2_588_147_712L
    const val MIN_VALID_SIZE_BYTES = 2_400_000_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _downloadState = MutableStateFlow<Gemma4DownloadState>(Gemma4DownloadState.NotDownloaded)
    val downloadState: StateFlow<Gemma4DownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var activeCall: Call? = null

    fun modelFile(context: Context): File {
        val base = context.getExternalFilesDir("models")
            ?: File(context.filesDir, "models")
        if (!base.exists()) base.mkdirs()
        return File(base, MODEL_FILE_NAME)
    }

    private fun partFile(context: Context): File {
        val target = modelFile(context)
        return File(target.parentFile, "$MODEL_FILE_NAME.part")
    }

    fun cacheDir(context: Context): File {
        return File(context.applicationContext.cacheDir, "litertlm_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    fun isModelReady(context: Context): Boolean {
        val file = modelFile(context)
        return file.exists() && file.length() >= MIN_VALID_SIZE_BYTES
    }

    fun refreshState(context: Context) {
        if (downloadJob?.isActive == true) return
        val file = modelFile(context)
        _downloadState.value = if (file.exists() && file.length() >= MIN_VALID_SIZE_BYTES) {
            Gemma4DownloadState.Ready(file.length())
        } else {
            Gemma4DownloadState.NotDownloaded
        }
    }

    fun startDownload(context: Context) {
        val appContext = context.applicationContext
        if (downloadJob?.isActive == true) return
        if (isModelReady(appContext)) {
            _downloadState.value = Gemma4DownloadState.Ready(modelFile(appContext).length())
            return
        }

        downloadJob = scope.launch {
            val target = modelFile(appContext)
            val part = partFile(appContext)
            var existingBytes = if (part.exists()) part.length() else 0L
            _downloadState.value = Gemma4DownloadState.Downloading(existingBytes, EXPECTED_SIZE_BYTES)

            try {
                val reqBuilder = Request.Builder().url(MODEL_DOWNLOAD_URL).get()
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                val call = httpClient.newCall(reqBuilder.build())
                activeCall = call

                call.execute().use { response ->
                    if (response.code == 416 && part.exists() && part.length() >= MIN_VALID_SIZE_BYTES) {
                        finalizePartFile(part, target)
                        _downloadState.value = Gemma4DownloadState.Ready(target.length())
                        return@launch
                    }

                    if (!response.isSuccessful && response.code != 206) {
                        error("HTTP ${response.code}")
                    }

                    val appendMode = response.code == 206 && existingBytes > 0L
                    if (!appendMode) {
                        existingBytes = 0L
                    }

                    val body = response.body ?: error("Empty response body")
                    val contentLength = body.contentLength().coerceAtLeast(0L)
                    val totalBytes = if (contentLength > 0L) existingBytes + contentLength else EXPECTED_SIZE_BYTES

                    var downloaded = existingBytes
                    var lastEmitAtMs = 0L
                    var lastEmitBytes = downloaded
                    val buffer = ByteArray(64 * 1024)

                    body.byteStream().use { input ->
                        FileOutputStream(part, appendMode).use { output ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmitAtMs >= 250L || downloaded - lastEmitBytes >= 1_048_576L) {
                                    lastEmitAtMs = now
                                    lastEmitBytes = downloaded
                                    _downloadState.value = Gemma4DownloadState.Downloading(downloaded, totalBytes)
                                }
                            }
                            output.flush()
                        }
                    }

                    if (part.length() < MIN_VALID_SIZE_BYTES) {
                        error("Incomplete download (${part.length()} bytes)")
                    }
                    finalizePartFile(part, target)
                    _downloadState.value = Gemma4DownloadState.Ready(target.length())
                }
            } catch (ce: CancellationException) {
                Log.i(TAG, "Model download cancelled")
                refreshState(appContext)
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Model download failed: ${t.message}", t)
                _downloadState.value = Gemma4DownloadState.Error(t.message ?: "Download failed")
            } finally {
                activeCall = null
            }
        }
    }

    fun cancelDownload() {
        runCatching { activeCall?.cancel() }
        activeCall = null
        downloadJob?.cancel()
        downloadJob = null
        if (_downloadState.value is Gemma4DownloadState.Downloading) {
            _downloadState.value = Gemma4DownloadState.NotDownloaded
        }
    }

    fun deleteModel(context: Context) {
        val appContext = context.applicationContext
        cancelDownload()
        scope.launch {
            Gemma4AudioTranscriber.unloadAndAwait()
            runCatching { modelFile(appContext).delete() }
            runCatching { partFile(appContext).delete() }
            runCatching { cacheDir(appContext).deleteRecursively() }
            refreshState(appContext)
        }
    }

    private fun finalizePartFile(part: File, target: File) {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            part.delete()
        }
    }
}
