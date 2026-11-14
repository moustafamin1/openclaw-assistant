package com.openclaw.assistant.ticktick

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GlossarySyncClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GlossarySyncClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GlossarySyncClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun syncFromUrlsUsesPrimaryMacBridgeWhenReachable() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "version": 1234,
                      "updatedAt": "2026-11-14T16:00:00Z",
                      "count": 2,
                      "entries": [
                        {"term": "Noory", "aliases": ["naughty", "nori"]},
                        {"term": "Yasmine", "aliases": []}
                      ]
                    }
                    """.trimIndent()
                )
        )

        val outcome = client.syncFromUrls(
            primaryUrl = server.url("/glossary.json").toString(),
            fallbackUrl = server.url("/fallback.json").toString(),
            authToken = "secret-pat"
        )

        assertTrue(outcome is GlossarySyncOutcome.Success)
        val success = outcome as GlossarySyncOutcome.Success
        assertEquals("Mac Bridge", success.sourceLabel)
        assertEquals(2, success.count)
        assertEquals("Noory = naughty, nori\nYasmine", success.formattedText)
        assertEquals(1, server.requestCount)
        assertEquals("/glossary.json", server.takeRequest().path)
    }

    @Test
    fun syncFromUrlsFallsBackToGitHubWithBearerTokenWhenPrimaryFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    [
                      {"term": "Karlsruhe", "aliases": ["carlsruhe"]},
                      "Antigravity"
                    ]
                    """.trimIndent()
                )
        )

        val outcome = client.syncFromUrls(
            primaryUrl = server.url("/glossary.json").toString(),
            fallbackUrl = server.url("/raw/ticktick-glossary.json").toString(),
            authToken = "ghp_test_token"
        )

        assertTrue(outcome is GlossarySyncOutcome.Success)
        val success = outcome as GlossarySyncOutcome.Success
        assertEquals("GitHub Fallback", success.sourceLabel)
        assertEquals(2, success.count)
        assertEquals("Karlsruhe = carlsruhe\nAntigravity", success.formattedText)

        val primaryReq = server.takeRequest()
        assertEquals("/glossary.json", primaryReq.path)
        assertEquals(null, primaryReq.getHeader("Authorization"))

        val fallbackReq = server.takeRequest()
        assertEquals("/raw/ticktick-glossary.json", fallbackReq.path)
        assertEquals("Bearer ghp_test_token", fallbackReq.getHeader("Authorization"))
    }

    @Test
    fun syncFromUrlsReturnsFailureWhenBothEndpointsFail() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(404))

        val outcome = client.syncFromUrls(
            primaryUrl = server.url("/glossary.json").toString(),
            fallbackUrl = server.url("/raw/ticktick-glossary.json").toString(),
            authToken = ""
        )

        assertTrue(outcome is GlossarySyncOutcome.Failure)
        val failure = outcome as GlossarySyncOutcome.Failure
        assertTrue(failure.reason.contains("Mac: HTTP 500"))
        assertTrue(failure.reason.contains("GitHub: HTTP 404"))
    }

    @Test
    fun syncFromUrlsRejectsHealthPayloadMissingEntriesAndFallsBack() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","service":"second-brain-voice-bridge"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"entries":[{"term":"Schöneberg","aliases":["schoenberg"]}]}""")
        )

        val outcome = client.syncFromUrls(
            primaryUrl = server.url("/glossary.json").toString(),
            fallbackUrl = server.url("/raw/ticktick-glossary.json").toString(),
            authToken = ""
        )

        assertTrue(outcome is GlossarySyncOutcome.Success)
        val success = outcome as GlossarySyncOutcome.Success
        assertEquals("GitHub Fallback", success.sourceLabel)
        assertEquals(1, success.count)
        assertEquals("Schöneberg = schoenberg", success.formattedText)
    }
}
