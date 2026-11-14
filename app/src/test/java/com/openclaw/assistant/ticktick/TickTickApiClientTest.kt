package com.openclaw.assistant.ticktick

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TickTickApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TickTickApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TickTickApiClient(apiBaseUrl = server.url("/open/v1").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun createInboxTaskPostsExpectedJsonAndAuthHeader() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"task_123","title":"Buy milk"}"""),
        )

        val result = client.createInboxTask("tok_abc", "Buy milk")
        assertTrue(result is TickTickCreateResult.Success)
        val success = result as TickTickCreateResult.Success
        assertEquals("task_123", success.taskId)
        assertEquals("Buy milk", success.title)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/open/v1/task", recorded.path)
        assertEquals("Bearer tok_abc", recorded.getHeader("Authorization"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("Buy milk", body["title"]?.jsonPrimitive?.content)
        assertEquals("inbox", body["projectId"]?.jsonPrimitive?.content)
        assertEquals(0, body["priority"]?.jsonPrimitive?.int)
    }

    @Test
    fun createInboxTaskReturnsUnauthorizedOn401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        val result = client.createInboxTask("bad_tok", "Call doctor")
        assertTrue(result is TickTickCreateResult.Unauthorized)
    }

    @Test
    fun createInboxTaskShortCircuitsBlankToken() = runTest {
        val result = client.createInboxTask("   ", "Call doctor")
        assertTrue(result is TickTickCreateResult.Unauthorized)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun testConnectionCountsProjects() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":"1"},{"id":"2"}]"""))
        val res = client.testConnection("tok_abc")
        assertTrue(res.isSuccess)
        assertEquals(2, res.getOrNull())
    }

    @Test
    fun cleanTranscriptNormalizesSpacingAndCase() {
        assertEquals("", TickTickCaptureRepository.cleanTranscript("   \n  "))
        assertEquals("Book dentist tomorrow", TickTickCaptureRepository.cleanTranscript("  book   dentist\ntomorrow "))
    }
}
