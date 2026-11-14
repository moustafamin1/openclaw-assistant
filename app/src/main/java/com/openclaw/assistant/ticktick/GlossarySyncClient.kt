package com.openclaw.assistant.ticktick

import android.content.Context
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class GlossaryEntryDto(
    val term: String,
    val aliases: List<String> = emptyList()
)

@Serializable
data class GlossaryPayloadDto(
    val version: Long = 0L,
    val updatedAt: String = "",
    val count: Int = 0,
    val entries: List<GlossaryEntryDto> = emptyList()
)

sealed class GlossarySyncOutcome {
    data class Success(
        val sourceLabel: String,
        val count: Int,
        val formattedText: String
    ) : GlossarySyncOutcome()

    data class Failure(val reason: String) : GlossarySyncOutcome()
}

class GlossarySyncClient(
    private val baseClient: OkHttpClient = OkHttpClient()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun syncAndSave(context: Context): GlossarySyncOutcome {
        val settings = SettingsRepository.getInstance(context)
        val outcome = syncFromUrls(
            primaryUrl = settings.tickTickGlossaryPrimaryUrl,
            fallbackUrl = settings.tickTickGlossaryFallbackUrl,
            authToken = settings.tickTickGlossaryAuthToken
        )
        when (outcome) {
            is GlossarySyncOutcome.Success -> {
                settings.tickTickSyncedGlossary = outcome.formattedText
                settings.tickTickGlossaryLastSyncAt = System.currentTimeMillis()
                settings.tickTickGlossaryLastStatus = "Synced ${outcome.count} entries (${outcome.sourceLabel})"
            }
            is GlossarySyncOutcome.Failure -> {
                settings.tickTickGlossaryLastStatus = "Failed: ${outcome.reason}"
            }
        }
        return outcome
    }

    suspend fun syncFromUrls(
        primaryUrl: String,
        fallbackUrl: String,
        authToken: String = ""
    ): GlossarySyncOutcome = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        val cleanPrimary = primaryUrl.trim()
        if (cleanPrimary.isNotEmpty()) {
            val primaryResult = fetchGlossary(
                url = cleanPrimary,
                timeoutMs = 2500L,
                authToken = ""
            )
            primaryResult.onSuccess { entries ->
                return@withContext GlossarySyncOutcome.Success(
                    sourceLabel = "Mac Bridge",
                    count = entries.size,
                    formattedText = formatEntries(entries)
                )
            }.onFailure { err ->
                errors += "Mac: ${err.message ?: err.javaClass.simpleName}"
            }
        }

        val cleanFallback = fallbackUrl.trim()
        if (cleanFallback.isNotEmpty()) {
            val fallbackResult = fetchGlossary(
                url = cleanFallback,
                timeoutMs = 10000L,
                authToken = authToken.trim()
            )
            fallbackResult.onSuccess { entries ->
                return@withContext GlossarySyncOutcome.Success(
                    sourceLabel = "GitHub Fallback",
                    count = entries.size,
                    formattedText = formatEntries(entries)
                )
            }.onFailure { err ->
                errors += "GitHub: ${err.message ?: err.javaClass.simpleName}"
            }
        }

        if (cleanPrimary.isEmpty() && cleanFallback.isEmpty()) {
            return@withContext GlossarySyncOutcome.Failure("No glossary URL configured")
        }

        GlossarySyncOutcome.Failure(errors.joinToString(" | ").ifEmpty { "Unknown error" })
    }

    private fun fetchGlossary(
        url: String,
        timeoutMs: Long,
        authToken: String
    ): Result<List<GlossaryEntryDto>> = runCatching {
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 1000L, TimeUnit.MILLISECONDS)
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")

        if (authToken.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $authToken")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            val body = response.body?.string()?.trim().orEmpty()
            if (body.isEmpty()) {
                error("Empty response")
            }
            parseGlossaryJson(body)
        }
    }

    internal fun parseGlossaryJson(rawJson: String): List<GlossaryEntryDto> {
        val root: JsonElement = json.parseToJsonElement(rawJson)
        val rawEntries = when (root) {
            is JsonObject -> {
                if (!root.containsKey("entries")) {
                    error("Glossary JSON missing 'entries' field")
                }
                json.decodeFromJsonElement<GlossaryPayloadDto>(root).entries
            }
            is JsonArray -> root.mapNotNull { elem ->
                when (elem) {
                    is JsonObject -> json.decodeFromJsonElement<GlossaryEntryDto>(elem)
                    is JsonPrimitive -> elem.content.trim().takeIf { it.isNotEmpty() }?.let {
                        GlossaryEntryDto(term = it)
                    }
                    else -> null
                }
            }
            else -> emptyList()
        }
        return rawEntries
            .mapNotNull { entry ->
                val cleanTerm = entry.term.trim()
                if (cleanTerm.isEmpty()) return@mapNotNull null
                val cleanAliases = entry.aliases
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals(cleanTerm, ignoreCase = true) }
                    .distinct()
                GlossaryEntryDto(term = cleanTerm, aliases = cleanAliases)
            }
    }

    internal fun formatEntries(entries: List<GlossaryEntryDto>): String {
        return entries.joinToString("\n") { entry ->
            if (entry.aliases.isEmpty()) {
                entry.term
            } else {
                "${entry.term} = ${entry.aliases.joinToString(", ")}"
            }
        }
    }
}
