package com.openclaw.assistant.ticktick

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

sealed class TickTickCreateResult {
    data class Success(val taskId: String, val title: String) : TickTickCreateResult()
    data class Unauthorized(val message: String) : TickTickCreateResult()
    data class HttpError(val statusCode: Int, val message: String) : TickTickCreateResult()
    data class NetworkError(val message: String) : TickTickCreateResult()
}

class TickTickApiClient(
    private val apiBaseUrl: String = DEFAULT_API_BASE,
    private val oauthApiBaseUrl: String = DEFAULT_OAUTH_API_BASE,
    private val oauthWebBaseUrl: String = DEFAULT_OAUTH_WEB_BASE,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun createInboxTask(
        accessToken: String,
        title: String,
        projectId: String = "inbox",
        priority: Int = 0,
    ): TickTickCreateResult = withContext(Dispatchers.IO) {
        val token = accessToken.trim()
        val cleanTitle = title.trim()
        if (token.isEmpty()) {
            return@withContext TickTickCreateResult.Unauthorized("TickTick access token is missing")
        }
        if (cleanTitle.isEmpty()) {
            return@withContext TickTickCreateResult.HttpError(400, "Task title is blank")
        }

        val payload = buildJsonObject {
            put("title", cleanTitle)
            put("projectId", projectId)
            put("priority", priority)
        }.toString()

        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/task")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val parsed = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                        val taskId = parsed?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val returnedTitle = parsed?.get("title")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: cleanTitle
                        TickTickCreateResult.Success(
                            taskId = taskId,
                            title = returnedTitle,
                        )
                    }
                    response.code == 401 -> {
                        TickTickCreateResult.Unauthorized("Unauthorized (401): $bodyStr")
                    }
                    else -> {
                        TickTickCreateResult.HttpError(
                            statusCode = response.code,
                            message = bodyStr.ifBlank { response.message },
                        )
                    }
                }
            }
        } catch (e: IOException) {
            TickTickCreateResult.NetworkError(e.message ?: "Network error")
        }
    }

    suspend fun testConnection(accessToken: String): Result<Int> = withContext(Dispatchers.IO) {
        val token = accessToken.trim()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Access token is empty"))
        }

        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/project")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("TickTick returned ${response.code}: ${bodyStr.take(200)}"),
                    )
                }
                val count = runCatching { json.parseToJsonElement(bodyStr).jsonArray.size }.getOrDefault(0)
                Result.success(count)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerOAuthClient(redirectUri: String): Result<String> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("client_name", "WakeHermesClaw Android")
            putJsonArray("redirect_uris") { add(redirectUri) }
        }.toString()
        val request = Request.Builder()
            .url("${oauthApiBaseUrl.trimEnd('/')}/register")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Client registration failed (${response.code}): $bodyStr"),
                    )
                }
                val clientId = json.parseToJsonElement(bodyStr)
                    .jsonObject["client_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (clientId.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Missing client_id in response"))
                }
                Result.success(clientId)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun generatePkceVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun computePkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildAuthorizeUrl(clientId: String, redirectUri: String, challenge: String): String {
        val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")
        return "${oauthWebBaseUrl.trimEnd('/')}/authorize" +
            "?client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
            "&response_type=code" +
            "&scope=tasks:read%20tasks:write" +
            "&redirect_uri=$encodedRedirect" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256"
    }

    suspend fun exchangeOAuthCode(
        clientId: String,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url("${oauthApiBaseUrl.trimEnd('/')}/token")
            .post(form)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Token exchange failed (${response.code}): $bodyStr"),
                    )
                }
                val token = json.parseToJsonElement(bodyStr)
                    .jsonObject["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (token.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Missing access_token in response"))
                }
                Result.success(token)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val DEFAULT_API_BASE = "https://api.ticktick.com/open/v1"
        const val DEFAULT_OAUTH_API_BASE = "https://api.ticktick.com/oauth"
        const val DEFAULT_OAUTH_WEB_BASE = "https://ticktick.com/oauth"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
