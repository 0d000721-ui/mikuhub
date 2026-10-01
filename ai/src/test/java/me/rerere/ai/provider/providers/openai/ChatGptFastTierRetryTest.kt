package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptFastTierRetryTest {
    private val body = JsonObject(mapOf("service_tier" to JsonPrimitive("fast")))
    private val rejection = HttpException("Unsupported service_tier: fast")
    private val chunk = StreamChunk.TextDelta("fixture", "Hello")

    @Test
    fun `a stream that emitted any chunk must never be replayed`() = runBlocking {
        var requests = 0
        val emitted = mutableListOf<StreamChunk>()
        val error = runCatching {
            withChatGptFastTierCompatibility(body) {
                flow {
                    requests++
                    emit(chunk)
                    throw ChatGptFastTierRejected(rejection)
                }
            }.collect { emitted.add(it) }
        }.exceptionOrNull()
        assertSame(rejection, error)
        assertEquals(1, requests)
        assertEquals(listOf(chunk), emitted)
    }

    @Test
    fun `replacement rejection never creates a third request or leaks its internal signal`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val error = runCatching {
            withChatGptFastTierCompatibility(body) { request ->
                flow {
                    requests.add(request)
                    throw ChatGptFastTierRejected(rejection)
                }
            }.toList()
        }.exceptionOrNull()
        assertSame(rejection, error)
        assertEquals(listOf("fast", "priority"), requests.map { (it["service_tier"] as JsonPrimitive).content })
    }

    @Test
    fun `cancellation between rejection and replacement prevents another request`() = runBlocking {
        var requests = 0
        supervisorScope {
            val operation = async {
                withChatGptFastTierCompatibility(body) {
                    flow {
                        requests++
                        currentCoroutineContext().cancel()
                        throw ChatGptFastTierRejected(rejection)
                    }
                }.toList()
            }
            assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
        }
        assertEquals(1, requests)
    }

    @Test
    fun `downstream errors never trigger an upstream retry`() = runBlocking {
        var requests = 0
        val downstreamError = ChatGptFastTierRejected(rejection)
        val error = runCatching {
            withChatGptFastTierCompatibility(body) {
                flow {
                    requests++
                    emit(chunk)
                }
            }.collect { throw downstreamError }
        }.exceptionOrNull()
        assertSame(downstreamError, error)
        assertEquals(1, requests)
    }

    @Test
    fun `each collection owns its output and retry state`() = runBlocking {
        var requests = 0
        val response = withChatGptFastTierCompatibility(body) { request ->
            flow {
                requests++
                if (request["service_tier"] == JsonPrimitive("fast")) throw ChatGptFastTierRejected(rejection)
                emit(chunk)
            }
        }
        assertEquals(listOf(chunk), response.toList())
        assertEquals(listOf(chunk), response.toList())
        assertEquals(4, requests)
    }

    @Test
    fun `only an exact structured fast spelling rejection is eligible`() {
        val accepted = listOf(
            """{"error":{"message":"Unsupported service_tier: fast"}}""",
            """{"message":"Unsupported service_tier: fast"}""",
            """{"detail":"Unsupported service_tier: fast"}""",
        )
        for (payload in accepted) {
            assertTrue(isChatGptFastTierSpellingRejection(true, body, 400, json.parseToJsonElement(payload)))
        }
        val rejected = listOf(
            """{"error":{"message":"Unsupported service_tier: priority"}}""",
            """{"error":{"message":"Unsupported service_tier: fast for this account"}}""",
            """{"error":{"code":"Unsupported service_tier: fast"}}""",
            """"Unsupported service_tier: fast"""",
            """{"unrelated":"Unsupported service_tier: fast"}""",
        )
        for (payload in rejected) {
            assertFalse(isChatGptFastTierSpellingRejection(true, body, 400, json.parseToJsonElement(payload)))
        }
        val payload = json.parseToJsonElement(accepted.first())
        assertFalse(isChatGptFastTierSpellingRejection(false, body, 400, payload))
        assertFalse(isChatGptFastTierSpellingRejection(true, body, 403, payload))
        assertFalse(isChatGptFastTierSpellingRejection(true, JsonObject(emptyMap()), 400, payload))
    }
}
