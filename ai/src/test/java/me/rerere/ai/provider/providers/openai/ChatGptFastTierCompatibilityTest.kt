package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChatGptFastTierCompatibilityTest {
    private val account = ProviderSetting.OpenAI(apiKey = "fixture-token", chatGptAccountId = "fixture-account")
    private val messages = listOf(UIMessage.user("fixture message"))

    @Test
    fun `explicit account fast spelling rejection retries priority once preserving the entire request`() = runBlocking {
        for (slug in listOf("gpt-6-astra", "gpt-6.1-sol")) {
            withServer(rejection(), success(slug)) { server, api ->
                val chunks = withTimeout(5_000) { api.streamText(account, messages, params(slug = slug)).toList() }
                assertTrue(chunks.any { it is StreamChunk.TextDelta })
                assertTrue(chunks.last() is StreamChunk.Finish)
                assertEquals(2, server.requestCount)
                val first = takeBody(server)
                val second = takeBody(server)
                assertEquals("fast", first.getValue("service_tier").jsonPrimitive.content)
                assertEquals("priority", second.getValue("service_tier").jsonPrimitive.content)
                assertEquals(first - "service_tier", second - "service_tier")
                assertEquals(slug, second.getValue("model").jsonPrimitive.content)
                assertEquals("high", second.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `account single result path also accepts the priority synonym after explicit rejection`() = runBlocking {
        withServer(rejection(detail = true), success()) { server, api ->
            val result = withTimeout(5_000) { api.generateText(account, messages, params()) }
            assertEquals("gpt-6-astra", result.model)
            assertEquals(2, server.requestCount)
            takeBody(server)
            assertEquals("priority", takeBody(server).getValue("service_tier").jsonPrimitive.content)
        }
    }

    @Test
    fun `priority rejection stops after the second request with the actual server error`() = runBlocking {
        withServer(rejection(), rejection(reason = "Unsupported service_tier: priority"), success()) { server, api ->
            val failure = failure(api, account)
            assertTrue(failure is HttpException)
            assertTrue(failure.message.orEmpty().contains("Unsupported service_tier: priority"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `other HTTP statuses never retry even if the reason mentions the fast spelling`() = runBlocking {
        for (status in listOf(401, 403, 429, 500)) {
            withServer(rejection(status = status), success()) { server, api ->
                failure(api, account)
                assertEquals("HTTP $status", 1, server.requestCount)
            }
        }
    }

    @Test
    fun `other 400 rejections never retry or downgrade the chosen tier`() = runBlocking {
        for (reason in listOf("Fast is unavailable for this workspace.", "Unsupported model", "Unsupported service_tier: ultrafast")) {
            withServer(rejection(reason = reason), success()) { server, api ->
                val failure = failure(api, account)
                assertTrue(failure is HttpException)
                assertTrue(failure.message.orEmpty().contains(reason))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `non fast requests never trigger the synonym fallback`() = runBlocking {
        for (tier in listOf("default", "priority", "ultrafast")) {
            withServer(rejection(), success()) { server, api ->
                failure(api, account, tier)
                assertEquals(tier, 1, server.requestCount)
            }
        }
    }

    @Test
    fun `API key fast requests are outside the account compatibility rule`() = runBlocking {
        withServer(rejection(), success()) { server, api ->
            failure(api, account.copy(chatGptAccountId = null, useResponseApi = true))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `SSE errors after partial output never replay a request`() = runBlocking {
        val partialFailure = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            delta + "data: {\"type\":\"error\",\"message\":\"Unsupported service_tier: fast\"}\n\n",
        )
        withServer(partialFailure, success()) { server, api ->
            val failure = failure(api, account)
            assertTrue(failure is HttpException)
            assertEquals(1, server.requestCount)
        }
    }

    private fun params(tier: String = "fast", slug: String = "gpt-6-astra") = TextGenerationParams(
        model = Model(modelId = slug, abilities = listOf(ModelAbility.REASONING)),
        customBody = listOf(CustomBody("service_tier", JsonPrimitive(tier))),
        reasoningLevel = ReasoningLevel.HIGH,
        sessionId = "fixture-session",
    )

    private suspend fun failure(api: ResponseAPI, setting: ProviderSetting.OpenAI, tier: String = "fast"): Throwable {
        return runCatching { withTimeout(5_000) { api.streamText(setting, messages, params(tier)).toList() } }.exceptionOrNull()
            ?: throw AssertionError("Expected a server rejection")
    }

    private fun rejection(status: Int = 400, reason: String = "Unsupported service_tier: fast", detail: Boolean = false) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(
            if (detail) """{"detail":"$reason"}"""
            else """{"error":{"message":"$reason","param":"service_tier"}}""",
        )

    private fun success(slug: String = "gpt-6-astra") = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        delta + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_fixture\",\"model\":\"$slug\",\"status\":\"completed\"}}\n\n",
    )

    private val delta = "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_fixture\",\"content_index\":0,\"delta\":\"Hello\"}\n\n"

    private fun takeBody(server: MockWebServer): JsonObject =
        json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject

    private suspend fun withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ResponseAPI) -> Unit) {
        MockWebServer().use { server ->
            responses.forEach(server::enqueue)
            server.start()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            try { block(server, ResponseAPI(client)) } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
    }
}
