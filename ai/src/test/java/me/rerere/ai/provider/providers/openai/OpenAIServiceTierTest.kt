package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAIServiceTierTest {
    private val account = ProviderSetting.OpenAI(apiKey = "fixture-token", chatGptAccountId = "fixture-account")
    private val apiKey = ProviderSetting.OpenAI(apiKey = "fixture-api-key", useResponseApi = true)
    private val messages = listOf(UIMessage.user("fixture message"))

    @Test
    fun `standard Fast Ultrafast and legacy priority travel as service tiers with unchanged model slugs`() = runBlocking {
        for (setting in listOf(account, apiKey)) {
            for (tier in listOf("default", "fast", "ultrafast", "priority")) {
                val model = model(tier)
                withServer(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(completed)) { server, client ->
                    withTimeout(5_000) {
                        OpenAIProvider(client).streamText(setting, messages, params(model)).toList()
                    }
                    val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                    val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    assertEquals("/v1/responses", request.path)
                    assertEquals("gpt-6-astra", body.getValue("model").jsonPrimitive.content)
                    assertEquals(tier, body.getValue("service_tier").jsonPrimitive.content)
                    assertTrue(body.getValue("stream").jsonPrimitive.boolean)
                    assertFalse(body.getValue("store").jsonPrimitive.boolean)
                }
            }
        }
    }

    @Test
    fun `account policy normalization retains selected tier and reasoning after custom body merge`() {
        for (tier in listOf("fast", "ultrafast", "priority")) {
            val model = model(tier)
            val body = ResponseAPI(OkHttpClient()).buildRequestBody(account, messages, params(model).copy(
                customBody = model.customBodies + listOf(
                    CustomBody("store", JsonPrimitive(true)),
                    CustomBody("stream", JsonPrimitive(false)),
                ),
            ), stream = false)
            assertEquals(tier, body.getValue("service_tier").jsonPrimitive.content)
            assertEquals("high", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            assertEquals("gpt-6-astra", body.getValue("model").jsonPrimitive.content)
            assertTrue(body.getValue("stream").jsonPrimitive.boolean)
            assertFalse(body.getValue("store").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `explicit standard model tier overrides inherited assistant Fast tier`() {
        val model = model("default")
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(account, messages, params(model).copy(
            customBody = listOf(CustomBody("service_tier", JsonPrimitive("fast"))) + model.customBodies,
        ), stream = true)
        assertEquals("default", body.getValue("service_tier").jsonPrimitive.content)
        assertEquals("gpt-6-astra", body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `API key single result Responses retains tier without forcing streaming`() = runBlocking {
        withServer(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"id":"resp_fixture","model":"gpt-6-astra","status":"completed","output":[]}""",
        )) { server, client ->
            withTimeout(5_000) { OpenAIProvider(client).generateText(apiKey, messages, params(model("ultrafast"))) }
            val body = json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            assertEquals("ultrafast", body.getValue("service_tier").jsonPrimitive.content)
            assertEquals("gpt-6-astra", body.getValue("model").jsonPrimitive.content)
            assertFalse(body.getValue("stream").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `saved model tier survives serialization without a fabricated speed model id`() {
        val saved = model("ultrafast")
        val restored = json.decodeFromString<Model>(json.encodeToString(saved))
        assertEquals(saved, restored)
        assertEquals("gpt-6-astra", restored.modelId)
        assertEquals("ultrafast", restored.customBodies.single().value.jsonPrimitive.content)
    }

    @Test
    fun `unsupported account tier preserves HTTP code parameter and server reason without network retries`() {
        val error = accountFailure(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody(
            """{"error":{"code":"subscription_sharing_unsupported_capability","param":"service_tier","message":"Ultrafast is unavailable for this workspace."},"unrelated":"private-fixture"}""",
        ))
        assertNonRetryable(error)
        assertTrue(error.message.orEmpty().contains("400"))
        assertTrue(error.message.orEmpty().contains("subscription_sharing_unsupported_capability"))
        assertTrue(error.message.orEmpty().contains("service_tier"))
        assertTrue(error.message.orEmpty().contains("Ultrafast is unavailable for this workspace."))
        assertFalse(error.message.orEmpty().contains("private-fixture"))
    }

    @Test
    fun `admission detail rejection remains a server failure and exposes its actual reason`() {
        val error = accountFailure(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody(
            """{"detail":"This service tier is unavailable for this workspace."}""",
        ))
        assertNonRetryable(error)
        assertTrue(error.message.orEmpty().contains("403"))
        assertTrue(error.message.orEmpty().contains("This service tier is unavailable for this workspace."))
    }

    @Test
    fun `HTTP rejection with an unrecognized body is never a retryable transport failure`() {
        val error = accountFailure(MockResponse().setResponseCode(403).setBody("<html>private-fixture</html>"))
        assertNonRetryable(error)
        assertTrue(error.message.orEmpty().contains("403"))
        assertFalse(error.message.orEmpty().contains("private-fixture"))
    }

    @Test
    fun `temporary server failure without a structured rejection remains retryable`() {
        val error = accountFailure(MockResponse().setResponseCode(503).setBody("<html>private-fixture</html>"))
        assertTrue(error is IOException)
        assertFalse(error.message.orEmpty().contains("private-fixture"))
    }

    @Test
    fun `account SSE tier rejection preserves its parameter and actual reason`() {
        for (payload in listOf(
            """{"type":"error","code":"subscription_sharing_unsupported_capability","param":"service_tier","message":"Ultrafast is unavailable."}""",
            """{"type":"response.failed","response":{"status":"failed","error":{"code":"subscription_sharing_unsupported_capability","param":"service_tier","message":"Ultrafast is unavailable."}}}""",
        )) {
            val error = assertThrows(Exception::class.java) {
                ResponseApiStreamDecoder(requireCompletedEvent = true).accept(SseEvent(data = payload))
            }
            assertNonRetryable(error)
            assertTrue(error.message.orEmpty().contains("subscription_sharing_unsupported_capability"))
            assertTrue(error.message.orEmpty().contains("service_tier"))
            assertTrue(error.message.orEmpty().contains("Ultrafast is unavailable."))
        }
    }

    private fun model(tier: String) = Model(
        modelId = "gpt-6-astra",
        abilities = listOf(ModelAbility.REASONING),
        customBodies = listOf(CustomBody("service_tier", JsonPrimitive(tier))),
    )

    private fun params(model: Model) = TextGenerationParams(
        model = model,
        customBody = model.customBodies,
        reasoningLevel = ReasoningLevel.HIGH,
    )

    private fun assertNonRetryable(error: Throwable) {
        assertTrue("A server rejection must stop the generation loop's network retries", error is HttpException)
        assertFalse(error is IOException)
    }

    private fun accountFailure(response: MockResponse): Throwable = assertThrows(Exception::class.java) {
        runBlocking {
            withServer(response) { server, client ->
                try {
                    withTimeout(5_000) { OpenAIProvider(client).streamText(account, messages, params(model("ultrafast"))).toList() }
                } finally {
                    assertEquals("An invalid tier must not trigger another HTTP request", 1, server.requestCount)
                }
            }
        }
    }

    private suspend fun withServer(response: MockResponse, block: suspend (MockWebServer, OkHttpClient) -> Unit) {
        MockWebServer().use { server ->
            server.enqueue(response)
            server.start()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            try { block(server, client) } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
    }

    private val completed = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_fixture\",\"model\":\"gpt-6-astra\",\"status\":\"completed\"}}\n\n"
}
