package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ChatGptResponsesContractTest {
    private fun accountSetting(): ProviderSetting.OpenAI = json.decodeFromString(
        """{"id":"25274964-274b-4e98-a730-f9c676c65a8e","apiKey":"ephemeral-test-token","chatGptAccountId":"account-local-id","useResponseApi":true}"""
    )

    @Test
    fun `account requests cannot override required streaming and storage policy`() {
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(
            accountSetting(), listOf(UIMessage.user("hello")),
            TextGenerationParams(Model(modelId = "account-model"), customBody = listOf(
                CustomBody("store", JsonPrimitive(true)), CustomBody("stream", JsonPrimitive(false)),
            )), stream = false,
        )
        assertFalse(body.getValue("store").jsonPrimitive.boolean)
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
    }

    @Test
    fun `account default reasoning avoids none on models that require reasoning`() {
        val api = ResponseAPI(OkHttpClient())
        for (slug in listOf("gpt-6-astra", "gpt-6.1-sol")) {
            val body = api.buildRequestBody(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(
                Model(modelId = slug, abilities = listOf(ModelAbility.REASONING)),
                temperature = 0.7f, topP = 0.9f, reasoningLevel = ReasoningLevel.OFF,
            ), stream = true)
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("top_p"))
            assertFalse(body.getValue("reasoning").let { it as kotlinx.serialization.json.JsonObject }.containsKey("effort"))
        }
    }

    @Test
    fun `account preview rejects sampling even when supported none effort is selected`() {
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(
            Model(modelId = "gpt-6-sol", abilities = listOf(ModelAbility.REASONING)),
            temperature = 0.7f, reasoningLevel = ReasoningLevel.OFF,
        ), stream = true)
        assertEquals("none", (body.getValue("reasoning") as kotlinx.serialization.json.JsonObject).getValue("effort").jsonPrimitive.content)
        assertFalse(body.containsKey("temperature"))
    }

    @Test
    fun `account reasoning cannot reintroduce incompatible sampling through custom body`() {
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(
            Model(modelId = "gpt-6-sol", abilities = listOf(ModelAbility.REASONING)),
            temperature = 0.7f, topP = 0.9f, reasoningLevel = ReasoningLevel.HIGH,
            customBody = listOf(CustomBody("temperature", JsonPrimitive(0.2)), CustomBody("top_p", JsonPrimitive(0.6))),
        ), stream = true)
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }

    @Test
    fun `subscription models use visible slugs and preserve account catalog order`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val client = recordingClient(requests, "application/json", """{"models":[
            {"slug":"second-model","display_name":"Second model","visibility":"list"},
            {"slug":"hidden-model","display_name":"Hidden","visibility":"hide"},
            {"slug":"first-model","display_name":"First model","visibility":"list"}
        ]}""")
        val models = OpenAIProvider(client).listModels(accountSetting())
        assertEquals(listOf("second-model", "first-model"), models.map { it.modelId })
        assertEquals(listOf("Second model", "First model"), models.map { it.displayName })
    }

    @Test
    fun `subscription bearer token bypasses persistent key roulette`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val roulette = object : KeyRoulette {
            override fun next(keys: String, providerId: String): String =
                throw AssertionError("Subscription credentials must not enter persistent roulette")
        }
        val api = ResponseAPI(recordingClient(requests, "text/event-stream", completedStream), roulette)
        val chunks = withTimeout(5_000) {
            api.streamText(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model"))).toList()
        }
        assertTrue(chunks.last() is StreamChunk.Finish)
        assertEquals("Bearer ephemeral-test-token", requests.single().header("Authorization"))
    }

    @Test
    fun `account credentials always target public Responses even with edited settings`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val setting = accountSetting().copy(baseUrl = "https://example.invalid/custom", responsesPath = "/steal", useResponseApi = false)
        withTimeout(5_000) {
            ResponseAPI(recordingClient(requests, "text/event-stream", completedStream))
                .streamText(setting, listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model"))).toList()
        }
        assertEquals("https://api.openai.com/v1/responses", requests.single().url.toString())
    }

    @Test
    fun `single result account requests aggregate streaming output and usage`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val provider = OpenAIProvider(recordingClient(requests, "text/event-stream", completedStream))
        val result = withTimeout(5_000) {
            provider.generateText(accountSetting().copy(useResponseApi = false), listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model")))
        }
        assertEquals("Hello", result.message.toText())
        assertEquals("resp_1", result.id)
        assertEquals(3, result.usage?.totalTokens)
        assertEquals("completed", result.finishReason)
        assertEquals("https://api.openai.com/v1/responses", requests.single().url.toString())
    }

    @Test
    fun `account stream ending before completed is an error`() {
        val interrupted = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"delta\":\"Partial\"}\n\n"
        val error = assertThrows(Exception::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    ResponseAPI(recordingClient(mutableListOf(), "text/event-stream", interrupted))
                        .streamText(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model"))).toList()
                }
            }
        }
        assertTrue(error.message.orEmpty().contains("response.completed"))
        assertTrue("A broken stream must remain a retryable transport failure", error is IOException)
    }

    @Test
    fun `DONE marker without completed is not successful account inference`() {
        val error = assertThrows(Exception::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    ResponseAPI(recordingClient(mutableListOf(), "text/event-stream", "data: [DONE]\n\n"))
                        .streamText(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model"))).toList()
                }
            }
        }
        assertTrue(error.message.orEmpty().contains("response.completed"))
    }

    @Test
    fun `mismatched completed SSE header does not complete account stream`() {
        val mismatched = "event: response.completed\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"delta\":\"Partial\"}\n\n"
        val error = assertThrows(Exception::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    ResponseAPI(recordingClient(mutableListOf(), "text/event-stream", mismatched))
                        .streamText(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model"))).toList()
                }
            }
        }
        assertTrue(error.message.orEmpty().contains("response.completed"))
    }

    @Test
    fun `account usage failure preserves recoverable subscription error code`() {
        val failed = "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"Usage exhausted\"}}}\n\n"
        val error = assertThrows(Exception::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    ResponseAPI(recordingClient(mutableListOf(), "text/event-stream", failed))
                        .streamText(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model"))).toList()
                }
            }
        }
        assertTrue(error.message.orEmpty().contains("subscription_sharing_usage_limit_exceeded"))
    }

    @Test
    fun `account incomplete response is not accepted as successful inference`() {
        for (reason in listOf("max_output_tokens", "content_filter")) {
            val incomplete = "event: response.incomplete\ndata: {\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_2\",\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"$reason\"}}}\n\n"
            val error = assertThrows(Exception::class.java) {
                runBlocking {
                    withTimeout(5_000) {
                        ResponseAPI(recordingClient(mutableListOf(), "text/event-stream", incomplete))
                            .streamText(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model"))).toList()
                    }
                }
            }
            assertTrue(error.message.orEmpty().contains(reason))
            assertTrue("A terminal rejection must not enter automatic network retries", error is HttpException)
            assertFalse(error is IOException)
        }
    }

    @Test
    fun `account bearer replaces conflicting custom authorization header`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        withTimeout(5_000) {
            ResponseAPI(recordingClient(requests, "text/event-stream", completedStream))
                .streamText(accountSetting(), listOf(UIMessage.user("hello")), TextGenerationParams(
                    Model(modelId = "account-model"), customHeaders = listOf(CustomHeader("Authorization", "Bearer stale-custom-key")),
                )).toList()
        }
        assertEquals(listOf("Bearer ephemeral-test-token"), requests.single().headers.values("Authorization"))
    }

    @Test
    fun `account credentials never reach balance endpoint`() {
        val requests = CopyOnWriteArrayList<Request>()
        val provider = OpenAIProvider(recordingClient(requests, "application/json", "{}"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { provider.getBalance(accountSetting()) }
        }
        assertTrue(error.message.orEmpty().contains("ChatGPT"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `API key model discovery retains standard data schema`() = runBlocking {
        val models = OpenAIProvider(recordingClient(mutableListOf(), "application/json", """{"data":[{"id":"legacy-model"}]}"""))
            .listModels(ProviderSetting.OpenAI(apiKey = "test-api-key"))
        assertEquals(listOf("legacy-model"), models.map { it.modelId })
    }

    @Test
    fun `account resolver refreshes each request without mutating saved settings`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        var refreshCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            val isModels = chain.request().method == "GET"
            val type = if (isModels) "application/json" else "text/event-stream"
            val body = if (isModels) """{"models":[{"slug":"account-model","display_name":"Account model","visibility":"list"}]}""" else completedStream
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", type)
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()
        val saved = accountSetting().copy(apiKey = "", baseUrl = "https://example.invalid", useResponseApi = false)
        val provider = OpenAIProvider(client, settingResolver = { setting ->
            refreshCount++
            setting.copy(apiKey = "fresh-token-$refreshCount", chatGptAccountId = null, baseUrl = "https://example.invalid")
        })
        withTimeout(5_000) {
            provider.listModels(saved)
            provider.generateText(saved, listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model")))
            provider.streamText(saved, listOf(UIMessage.user("hello")), TextGenerationParams(Model(modelId = "account-model"))).toList()
        }
        assertEquals(listOf("Bearer fresh-token-1", "Bearer fresh-token-2", "Bearer fresh-token-3"), requests.map { it.header("Authorization") })
        assertEquals(listOf("https://api.openai.com/v1/models", "https://api.openai.com/v1/responses", "https://api.openai.com/v1/responses"), requests.map { it.url.toString() })
        assertEquals("", saved.apiKey)
        assertEquals("account-local-id", saved.chatGptAccountId)
    }

    @Test
    fun `API key providers do not invoke account resolver`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val provider = OpenAIProvider(recordingClient(requests, "application/json", """{"data":[{"id":"legacy-model"}]}"""), settingResolver = {
            throw AssertionError("Manual API keys must not resolve account credentials")
        })
        provider.listModels(ProviderSetting.OpenAI(apiKey = "test-api-key", baseUrl = "https://example.invalid/v1"))
        assertEquals("https://example.invalid/v1/models", requests.single().url.toString())
        assertEquals("Bearer test-api-key", requests.single().header("Authorization"))
    }

    @Test
    fun `account credentials cannot be used for independent embedding or image endpoints`() {
        val requests = CopyOnWriteArrayList<Request>()
        val provider = OpenAIProvider(recordingClient(requests, "application/json", "{}"))
        val model = Model(modelId = "account-model")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { provider.generateEmbedding(accountSetting(), EmbeddingGenerationParams(model, listOf("hello"))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { provider.generateImage(accountSetting(), ImageGenerationParams(model, "hello")).toList() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { provider.editImage(accountSetting(), ImageEditParams(model, "hello", listOf("fake-image.png"))).toList() }
        }
        assertTrue(requests.isEmpty())
    }

    private fun recordingClient(requests: MutableList<Request>, type: String, payload: String) =
        OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", type)
                .body(payload.toResponseBody(type.toMediaType())).build()
        }.build()

    private val completedStream = """
        event: response.output_text.delta
        data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"Hello"}

        event: response.completed
        data: {"type":"response.completed","response":{"id":"resp_1","model":"account-model","status":"completed","usage":{"input_tokens":2,"output_tokens":1,"total_tokens":3}}}


    """.trimIndent()
}
