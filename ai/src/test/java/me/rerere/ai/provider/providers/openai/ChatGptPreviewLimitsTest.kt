package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ChatGptPreviewLimitsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = ProviderSetting.OpenAI(apiKey = "fixture-token", chatGptAccountId = "fixture-account")
    private val apiKey = ProviderSetting.OpenAI(apiKey = "fixture-api-key", useResponseApi = true)
    private val messages = listOf(UIMessage.user("fixture message"))
    private val plainModel = Model(modelId = "future-account-model", abilities = listOf(ModelAbility.REASONING))
    private val unsupported = listOf("max_output_tokens", "temperature", "top_p", "top_logprobs")

    @Test
    fun `account preview removes unsupported generation parameters even with none effort`() {
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(account, messages, TextGenerationParams(
            plainModel, maxTokens = 500, temperature = 0.7f, topP = 0.9f, reasoningLevel = ReasoningLevel.OFF,
        ), stream = true)
        unsupported.forEach { assertFalse("Account preview does not support $it", body.containsKey(it)) }
        assertEquals("none", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `custom body cannot reintroduce unsupported account preview parameters`() {
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(account, messages, TextGenerationParams(
            plainModel,
            customBody = unsupported.map { CustomBody(it, JsonPrimitive(1)) } +
                CustomBody("service_tier", JsonPrimitive("fast")),
        ), stream = true)
        unsupported.forEach { assertFalse("Custom body must not reintroduce $it", body.containsKey(it)) }
        assertEquals("fast", body.getValue("service_tier").jsonPrimitive.content)
    }

    @Test
    fun `API key Responses keeps its generation parameters and custom sampling overrides`() {
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(apiKey, messages, TextGenerationParams(
            plainModel, maxTokens = 500, temperature = 0.7f, topP = 0.9f,
            customBody = listOf(CustomBody("top_logprobs", JsonPrimitive(2)), CustomBody("temperature", JsonPrimitive(0.4))),
        ), stream = true)
        assertEquals(500, body.getValue("max_output_tokens").jsonPrimitive.content.toInt())
        assertEquals(0.4, body.getValue("temperature").jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals(0.9, body.getValue("top_p").jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals(2, body.getValue("top_logprobs").jsonPrimitive.content.toInt())
    }

    @Test
    fun `account image generation selection is rejected before any request`() {
        assertImageRejected(plainModel.copy(tools = setOf(BuiltInTools.ImageGeneration)), emptyList())
    }

    @Test
    fun `custom tools cannot bypass account image generation capability restriction`() {
        assertImageRejected(plainModel, listOf(CustomBody("tools", imageTools)))
    }

    @Test
    fun `custom tools override cannot disguise account image generation selection`() {
        assertImageRejected(plainModel.copy(tools = setOf(BuiltInTools.ImageGeneration)), listOf(CustomBody("tools", JsonArray(emptyList()))))
    }

    @Test
    fun `API key Responses image generation remains supported with either tool configuration`() {
        val api = ResponseAPI(OkHttpClient())
        val builtIn = api.buildRequestBody(apiKey, messages, TextGenerationParams(
            plainModel.copy(tools = setOf(BuiltInTools.ImageGeneration)),
        ), stream = true)
        val custom = api.buildRequestBody(apiKey, messages, TextGenerationParams(
            plainModel, customBody = listOf(CustomBody("tools", imageTools)),
        ), stream = true)
        assertTrue(builtIn.getValue("tools").toString().contains("image_generation"))
        assertEquals(imageTools, custom.getValue("tools"))
    }

    @Test
    fun `account catalog refresh bypasses cached models and forbids credential response storage`() = runBlocking {
        withCachedServer { server, client, cache ->
            server.enqueue(catalog("gpt-6-astra"))
            server.enqueue(catalog("gpt-6.1-sol"))
            val provider = OpenAIProvider(client)
            assertEquals(listOf("gpt-6-astra"), withTimeout(5_000) { provider.listModels(account) }.map { it.modelId })
            assertEquals(listOf("gpt-6.1-sol"), withTimeout(5_000) { provider.listModels(account) }.map { it.modelId })
            assertEquals(2, server.requestCount)
            repeat(2) {
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                val cacheControl = CacheControl.parse(request.headers)
                assertTrue(cacheControl.noCache)
                assertTrue(cacheControl.noStore)
            }
            assertFalse("OAuth model response must not remain on disk", cache.urls().hasNext())
        }
    }

    @Test
    fun `API key catalog retains normal HTTP caching behavior`() = runBlocking {
        withCachedServer { server, client, _ ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setHeader("Cache-Control", "public, max-age=86400")
                .setBody("""{"data":[{"id":"key-model"}]}"""))
            val provider = OpenAIProvider(client)
            repeat(2) {
                assertEquals(listOf("key-model"), withTimeout(5_000) { provider.listModels(apiKey) }.map { it.modelId })
            }
            assertEquals(1, server.requestCount)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            val cacheControl = CacheControl.parse(request.headers)
            assertFalse(cacheControl.noCache)
            assertFalse(cacheControl.noStore)
        }
    }

    private fun assertImageRejected(model: Model, customBody: List<CustomBody>) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
            ))
            server.start()
            val client = client(server)
            try {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        withTimeout(5_000) { OpenAIProvider(client).streamText(account, messages, TextGenerationParams(model, customBody = customBody)).toList() }
                    }
                }
                assertTrue(error.message.orEmpty().contains("ChatGPT"))
                assertTrue(error.message.orEmpty().contains("图像"))
                assertEquals("Unsupported account tools must not reach the server", 0, server.requestCount)
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun catalog(slug: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setHeader("Cache-Control", "public, max-age=86400")
        .setBody("""{"models":[{"slug":"$slug","display_name":"$slug","visibility":"list"}]}""")

    private suspend fun withCachedServer(block: suspend (MockWebServer, OkHttpClient, Cache) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            Cache(temporary.newFolder(), 1024L * 1024).use { cache ->
                val client = client(server, cache)
                try { block(server, client, cache) } finally {
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    private fun client(server: MockWebServer, cache: Cache? = null) = OkHttpClient.Builder().cache(cache)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()

    private val imageTools = json.parseToJsonElement("""[{"type":"image_generation"}]""")
}
