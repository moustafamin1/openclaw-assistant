package com.openclaw.assistant.ticktick

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Manages the offline 3-layer context index on Android:
 *   1. Bundled APK asset (`ticktick-context-index.json`)
 *   2. Mac Bridge / GitHub synced index (`ticktick_context_synced.json`)
 *   3. On-device incremental captures + active TickTick task sync (`ticktick_context_local.json`)
 */
object ContextIndexManager {
    private const val BUNDLED_ASSET_NAME = "ticktick-context-index.json"
    private const val SYNCED_FILE_NAME = "ticktick_context_synced.json"
    private const val LOCAL_FILE_NAME = "ticktick_context_local_v2.json"
    private const val LEGACY_LOCAL_FILE_NAME = "ticktick_context_local.json"
    private const val MAX_LOCAL_UNIGRAMS = 1500
    private const val MAX_LOCAL_BIGRAMS = 2500

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedIndex: ContextIndex? = null

    fun getIndex(context: Context): ContextIndex {
        cachedIndex?.let { return it }
        synchronized(lock) {
            cachedIndex?.let { return it }
            val appCtx = context.applicationContext
            runCatching {
                val legacy = File(appCtx.filesDir, LEGACY_LOCAL_FILE_NAME)
                if (legacy.exists()) legacy.delete()
            }
            val bundledAsset = readBundledAsset(appCtx)
            val syncedAsset = readAssetFile(File(appCtx.filesDir, SYNCED_FILE_NAME))
            val activeBase = when {
                syncedAsset != null &&
                    syncedAsset.contextIndex.unigrams.isNotEmpty() &&
                    syncedAsset.version >= (bundledAsset?.version ?: 0L) -> syncedAsset.contextIndex
                bundledAsset != null && bundledAsset.contextIndex.unigrams.isNotEmpty() -> bundledAsset.contextIndex
                else -> syncedAsset?.contextIndex
            }
            val local = readLocalFile(File(appCtx.filesDir, LOCAL_FILE_NAME))
            return ContextLanguageModel.mergePayloads(activeBase, local).also {
                cachedIndex = it
            }
        }
    }

    fun saveSyncedIndex(
        context: Context,
        dto: ContextIndexPayloadDto?,
        version: Long = System.currentTimeMillis() / 1000L,
    ) {
        if (dto == null || dto.unigrams.isEmpty()) return
        val appCtx = context.applicationContext
        synchronized(lock) {
            runCatching {
                val asset = StandaloneContextAssetDto(version = version, contextIndex = dto)
                File(appCtx.filesDir, SYNCED_FILE_NAME)
                    .writeText(ContextLanguageModel.encodeStandaloneAsset(asset))
            }
            cachedIndex = null
        }
    }

    /**
     * Immediately reinforces unigram & bigram weights after saving a task (works 100% offline).
     */
    fun recordSentence(context: Context, sentence: String, weight: Double = 1.5) {
        if (sentence.isBlank()) return
        val appCtx = context.applicationContext
        synchronized(lock) {
            val file = File(appCtx.filesDir, LOCAL_FILE_NAME)
            val current = readLocalFile(file) ?: ContextIndexPayloadDto()
            val updated = accumulateSentences(current, listOf(sentence), weight)
            runCatching {
                file.writeText(ContextLanguageModel.encodePayload(updated))
            }
            cachedIndex = null
        }
    }

    /**
     * Refreshes local context index directly from user's active TickTick projects using OAuth token.
     */
    fun syncActiveTickTickProjects(context: Context, accessToken: String, client: OkHttpClient = OkHttpClient()): Int {
        if (accessToken.isBlank()) return 0
        val shortClient = client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        val projectIds = mutableListOf("inbox")
        val titles = mutableListOf<String>()

        runCatching {
            val req = Request.Builder()
                .url("https://api.ticktick.com/open/v1/project")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            shortClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val arr = json.parseToJsonElement(body) as? JsonArray ?: JsonArray(emptyList())
                    for (elem in arr) {
                        val obj = elem as? JsonObject ?: continue
                        val id = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                        if (id.isNotEmpty()) projectIds += id
                        if (name.isNotEmpty()) titles += name
                    }
                }
            }
        }

        for (pid in projectIds.distinct()) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.ticktick.com/open/v1/project/$pid/data")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                shortClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val obj = json.parseToJsonElement(body) as? JsonObject ?: return@use
                    val tasks = obj["tasks"] as? JsonArray ?: return@use
                    for (t in tasks) {
                        val taskObj = t as? JsonObject ?: continue
                        val title = taskObj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                        if (title.isNotEmpty()) titles += title
                    }
                }
            }
        }

        if (titles.isEmpty()) return 0
        val appCtx = context.applicationContext
        synchronized(lock) {
            val file = File(appCtx.filesDir, LOCAL_FILE_NAME)
            val updated = accumulateSentences(ContextIndexPayloadDto(), titles, weight = 0.8)
            runCatching {
                file.writeText(ContextLanguageModel.encodePayload(updated))
            }
            cachedIndex = null
        }
        return titles.size
    }

    internal fun accumulateSentences(
        base: ContextIndexPayloadDto,
        sentences: List<String>,
        weight: Double,
    ): ContextIndexPayloadDto {
        val uni = base.unigrams.toMutableMap()
        val bi = base.bigrams.toMutableMap()

        for (sentence in sentences) {
            val cleaned = sentence.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " ")
                .replace("ദ", " ")
                .replace("☑️", " ")
            val tokens = Regex("[A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß0-9'-]*")
                .findAll(cleaned)
                .map { it.value.trim('-', '\'') }
                .filter { it.isNotEmpty() && it.length <= 26 }
                .toList()
            for (i in tokens.indices) {
                val raw = tokens[i]
                val lower = raw.lowercase(Locale.ROOT)
                val existing = uni[lower]
                val cap = if (existing != null && existing.c.isNotEmpty()) {
                    existing.c
                } else if (i > 0 && raw.any { it.isUpperCase() }) {
                    raw
                } else {
                    lower
                }
                val nextW = ((existing?.w ?: 0.0) + weight).coerceAtMost(50.0)
                uni[lower] = ContextUnigramDto(w = nextW, c = cap)

                if (i + 1 < tokens.size) {
                    val nextLower = tokens[i + 1].lowercase(Locale.ROOT)
                    val pair = "$lower $nextLower"
                    bi[pair] = ((bi[pair] ?: 0.0) + weight).coerceAtMost(50.0)
                }
            }
        }

        val cappedUni = if (uni.size > MAX_LOCAL_UNIGRAMS) {
            uni.entries.sortedByDescending { it.value.w }.take(MAX_LOCAL_UNIGRAMS)
                .associate { it.key to it.value }
        } else {
            uni
        }
        val cappedBi = if (bi.size > MAX_LOCAL_BIGRAMS) {
            bi.entries.sortedByDescending { it.value }.take(MAX_LOCAL_BIGRAMS)
                .associate { it.key to it.value }
        } else {
            bi
        }
        return ContextIndexPayloadDto(cappedUni, cappedBi)
    }

    private fun readBundledAsset(context: Context): StandaloneContextAssetDto? {
        return runCatching {
            context.assets.open(BUNDLED_ASSET_NAME).bufferedReader().use { reader ->
                ContextLanguageModel.parseStandaloneAsset(reader.readText())
            }
        }.getOrNull()
    }

    private fun readAssetFile(file: File): StandaloneContextAssetDto? {
        if (!file.exists()) return null
        return runCatching {
            ContextLanguageModel.parseStandaloneAsset(file.readText())
        }.getOrNull()
    }

    private fun readLocalFile(file: File): ContextIndexPayloadDto? {
        if (!file.exists()) return null
        return runCatching {
            ContextLanguageModel.parseStandaloneJson(file.readText())
        }.getOrNull()
    }
}
