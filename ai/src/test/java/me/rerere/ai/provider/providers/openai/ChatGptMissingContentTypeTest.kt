package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real HTTP responses reproduce the official Responses endpoint returning SSE without a media type. */
class ChatGptMissingContentTypeTest {
    private val account = ProviderSetting.OpenAI(apiKey = "fixture-token", chatGptAccountId = "fixture-account")
    private val params = TextGenerationParams(Model(modelId = "fixture-model"))
    private val messages = listOf(UIMessage.user("fixture message"))

    @Test
    fun `headerless official account Responses preserves text usage and completion`() = runBlocking {
        withServer(MockResponse().setBody(created + delta + completed)) { server, client ->
            val result = withTimeout(5_000) { OpenAIProvider(client).generateText(account, messages, params) }
            assertEquals("Hello", result.message.toText())
            assertEquals("resp_fixture", result.id)
            assertEquals(3, result.usage?.totalTokens)
            assertEquals("completed", result.finishReason)
            assertEquals("/v1/responses", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test
    fun `headerless created event can contain large instructions without losing following deltas`() = runBlocking {
        val largeCreated = "data: {\"type\":\"response.created\",\"response\":{\"instructions\":\"${"fixture ".repeat(20_000)}\"}}\n\n"
        withServer(MockResponse().setBody(largeCreated + delta + completed)) { _, client ->
            val result = withTimeout(5_000) { OpenAIProvider(client).generateText(account, messages, params) }
            assertEquals("Hello", result.message.toText())
        }
    }

    @Test
    fun `headerless SSE permits keepalive comments CRLF and multiline data`() = runBlocking {
        val prefix = ": keepalive\r\n\r\nevent: response.created\r\ndata: {\"type\":\"response.created\",\r\ndata: \"response\":{\"status\":\"in_progress\"}}\r\n\r\n"
        withServer(MockResponse().setBody(prefix + delta + completed)) { _, client ->
            val result = withTimeout(5_000) { OpenAIProvider(client).generateText(account, messages, params) }
            assertEquals("Hello", result.message.toText())
        }
    }

    @Test
    fun `headerless SSE remains streaming rather than waiting for the complete body`() = runBlocking {
        // A full socket buffer is sent before the pause, so the server itself cannot buffer our
        // tiny first event until the response ends and make this a transport timing false positive.
        val initial = delta + ": ${"keepalive".repeat(2_000)}\n\n"
        val response = MockResponse().setBody(initial + completed)
            .throttleBody(initial.toByteArray().size.toLong(), 2, TimeUnit.SECONDS)
        withServer(response) { _, client ->
            val firstDelta = CompletableDeferred<String>()
            val request = async(Dispatchers.Default) {
                OpenAIProvider(client).streamText(account, messages, params).collect {
                    if (it is StreamChunk.TextDelta) firstDelta.complete(it.text)
                }
            }
            try {
                assertEquals("Hello", withTimeout(1_500) { firstDelta.await() })
                assertFalse("Response must still be waiting for its completed event", request.isCompleted)
                withTimeout(5_000) { request.await() }
            } finally {
                request.cancel()
            }
        }
    }

    @Test
    fun `headerless truncated account stream still fails without completed`() {
        val error = failure(MockResponse().setBody(created + delta))
        assertTrue(error.message.orEmpty().contains("response.completed"))
    }

    @Test
    fun `headerless ordinary JSON cannot become successful SSE`() {
        failure(MockResponse().setBody("""{"type":"response.completed","response":{"status":"completed"}}"""))
    }

    @Test
    fun `headerless HTML cannot hide a completed SSE event in its body`() {
        failure(MockResponse().setBody("<html>proxy error</html>\n\n" + completed))
    }

    @Test
    fun `explicit non SSE content type is never overridden`() {
        failure(MockResponse().setHeader("Content-Type", "application/json").setBody(created + delta + completed))
    }

    @Test
    fun `headerless fallback does not apply to ordinary API keys`() {
        failure(MockResponse().setBody(delta + completed), account.copy(chatGptAccountId = null, useResponseApi = true))
    }

    @Test
    fun `HTTP failures cannot be reclassified as successful headerless SSE`() {
        failure(MockResponse().setResponseCode(500).setBody(delta + completed))
    }

    @Test
    fun `headerless data with another protocol type cannot become Responses SSE`() {
        failure(MockResponse().setBody("data: {\"type\":\"message\",\"text\":\"wrong protocol\"}\n\n" + completed))
    }

    @Test
    fun `valid typed SSE remains unchanged`() = runBlocking {
        withServer(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(delta + completed)) { _, client ->
            val chunks = withTimeout(5_000) { OpenAIProvider(client).streamText(account, messages, params).toList() }
            assertEquals("Hello", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
            assertTrue(chunks.last() is StreamChunk.Finish)
        }
    }

    @Test
    fun `sign out cancels headerless prefix inspection and its HTTP call`() = runBlocking {
        val session = Job()
        val canceled = CompletableDeferred<Unit>()
        val listener = object : EventListener() {
            override fun canceled(call: Call) { canceled.complete(Unit) }
        }
        withServer(MockResponse().setBody(created + delta + completed).setBodyDelay(3, TimeUnit.SECONDS), listener) { server, client ->
            val request = async(Dispatchers.Default) {
                OpenAIProvider(client).streamText(account.copy(chatGptRequestJob = session), messages, params).toList()
            }
            try {
                assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)
                session.cancel()
                withTimeout(2_000) { canceled.await(); request.join() }
                assertTrue(request.isCancelled)
                assertTrue(coroutineContext[Job]!!.isActive)
            } finally {
                session.cancel()
                request.cancel()
            }
        }
    }

    private fun failure(response: MockResponse, setting: ProviderSetting.OpenAI = account): Throwable =
        assertThrows(Exception::class.java) {
            runBlocking {
                withServer(response) { _, client ->
                    withTimeout(5_000) { OpenAIProvider(client).streamText(setting, messages, params).toList() }
                }
            }
        }

    private suspend fun withServer(
        response: MockResponse,
        listener: EventListener = EventListener.NONE,
        block: suspend (MockWebServer, OkHttpClient) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.enqueue(response)
            server.start()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.eventListener(listener).build()
            try { block(server, client) } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
    }

    private val created = "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_fixture\",\"status\":\"in_progress\"}}\n\n"
    private val delta = "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_fixture\",\"delta\":\"Hello\"}\n\n"
    private val completed = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_fixture\",\"model\":\"fixture-model\",\"status\":\"completed\",\"usage\":{\"input_tokens\":2,\"output_tokens\":1,\"total_tokens\":3}}}\n\n"
}
