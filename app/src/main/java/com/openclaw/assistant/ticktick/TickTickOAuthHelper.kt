package com.openclaw.assistant.ticktick

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * Runs TickTick PKCE OAuth via an ephemeral loopback HTTP listener on 127.0.0.1.
 * Avoids embedding any client secret in the APK.
 */
class TickTickOAuthHelper(
    private val apiClient: TickTickApiClient = TickTickApiClient(),
) {
    suspend fun authenticateInBrowser(
        context: Context,
        existingClientId: String,
        onClientIdCreated: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val server = runCatching {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 120_000
            }
        }.getOrElse { return@withContext Result.failure(it) }

        try {
            val port = server.localPort
            val redirectUri = "http://127.0.0.1:$port/callback"

            val clientId = existingClientId.takeIf { it.isNotBlank() }
                ?: apiClient.registerOAuthClient(redirectUri)
                    .getOrElse { return@withContext Result.failure(it) }
                    .also(onClientIdCreated)

            val verifier = apiClient.generatePkceVerifier()
            val challenge = apiClient.computePkceChallenge(verifier)
            val authorizeUrl = apiClient.buildAuthorizeUrl(clientId, redirectUri, challenge)

            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }

            val code = awaitCallbackCode(server)
                ?: return@withContext Result.failure(IllegalStateException("Authorization cancelled or missing code"))

            apiClient.exchangeOAuthCode(
                clientId = clientId,
                code = code,
                redirectUri = redirectUri,
                codeVerifier = verifier,
            )
        } finally {
            runCatching { server.close() }
        }
    }

    private fun awaitCallbackCode(server: ServerSocket): String? {
        while (true) {
            val socket = server.accept()
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return@use
                val pathAndQuery = requestLine.split(" ").getOrNull(1).orEmpty()
                if (!pathAndQuery.startsWith("/callback")) {
                    writeHtmlResponse(client, 404, "Not Found")
                    return@use
                }
                val query = pathAndQuery.substringAfter("?", "")
                val params = parseQueryParams(query)
                val code = params["code"]
                if (code.isNullOrBlank()) {
                    val error = params["error"] ?: "Authorization failed"
                    writeHtmlResponse(client, 400, "<h2>TickTick sign-in failed: $error</h2>")
                    return null
                }
                writeHtmlResponse(
                    client,
                    200,
                    "<h2>TickTick connected</h2><p>You can return to WakeHermesClaw now.</p>",
                )
                return code
            }
        }
    }

    private fun writeHtmlResponse(socket: java.net.Socket, status: Int, htmlBody: String) {
        val body = "<!doctype html><html><body style='font-family:sans-serif;text-align:center;padding:40px;'>$htmlBody</body></html>"
        val statusText = if (status == 200) "OK" else "Error"
        val headers = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.UTF_8))
            write(body.toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    companion object {
        fun parseQueryParams(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split("&")
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null
                    else {
                        val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                        val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                        key to value
                    }
                }
                .toMap()
        }
    }
}
