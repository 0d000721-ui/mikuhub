package me.rerere.ai.provider.providers.openai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatGptSessionCancellationTest {
    private val params = TextGenerationParams(Model(modelId = "account-model"))
    private val messages = listOf(UIMessage.user("hello"))

    @Test
    fun `sign out cancels selected account stream and leaves other account and API key active`() = runBlocking {
        DelayedServer().use { server ->
            val accountOne = Job()
            val accountTwo = Job()
            val provider = OpenAIProvider(server.client)
            val firstChunk = CompletableDeferred<Unit>()
            val secondChunk = CompletableDeferred<Unit>()
            val keyChunk = CompletableDeferred<Unit>()
            val first = async {
                provider.streamText(account("first", accountOne), messages, params).collect {
                    if (it is StreamChunk.TextDelta) firstChunk.complete(Unit)
                }
            }
            val second = async {
                provider.streamText(account("second", accountTwo), messages, params).collect {
                    if (it is StreamChunk.TextDelta) secondChunk.complete(Unit)
                }
            }
            val manualKey = async {
                provider.streamText(ProviderSetting.OpenAI(apiKey = "manual", useResponseApi = true, chatGptRequestJob = accountOne), messages, params).collect {
                    if (it is StreamChunk.TextDelta) keyChunk.complete(Unit)
                }
            }
            try {
                withTimeout(5_000) { firstChunk.await(); secondChunk.await(); keyChunk.await() }
                accountOne.cancel()
                delay(100)
                assertTrue("Selected account request must be canceled on sign out", first.isCancelled)
                assertTrue("Selected account HTTP transport must be canceled", server.canceled("first"))
                assertFalse(second.isCompleted)
                assertFalse(manualKey.isCompleted)
                assertTrue(accountTwo.isActive)
                assertTrue(coroutineContext[Job]!!.isActive)
                server.finish("second")
                server.finish("manual")
                withTimeout(5_000) { second.await(); manualKey.await() }
            } finally {
                accountOne.cancel(); accountTwo.cancel()
                first.cancel(); second.cancel(); manualKey.cancel()
            }
        }
    }

    @Test
    fun `already signed out session prevents catalog and inference HTTP requests`() = runBlocking {
        DelayedServer().use { server ->
            val session = Job().apply { cancel() }
            val provider = OpenAIProvider(server.client)
            val setting = account("first", session)
            val catalogError = runCatching { withTimeout(2_000) { provider.listModels(setting) } }.exceptionOrNull()
            val inferenceError = runCatching { withTimeout(2_000) { provider.generateText(setting, messages, params) } }.exceptionOrNull()
            assertTrue(catalogError is CancellationException)
            assertTrue(inferenceError is CancellationException)
            assertEquals(0, server.requestCount.get())
            assertTrue(coroutineContext[Job]!!.isActive)
        }
    }

    @Test
    fun `sign out cancels delayed catalog HTTP without canceling caller`() = runBlocking {
        DelayedServer().use { server ->
            val session = Job()
            val provider = OpenAIProvider(server.client)
            val catalog = async { provider.listModels(account("catalog", session)) }
            try {
                withTimeout(5_000) { server.catalogStarted.await() }
                session.cancel()
                delay(100)
                assertTrue(catalog.isCancelled)
                assertTrue(server.canceled("catalog"))
                assertTrue(coroutineContext[Job]!!.isActive)
            } finally {
                session.cancel(); catalog.cancel()
            }
        }
    }

    private fun account(token: String, job: Job) = ProviderSetting.OpenAI(
        apiKey = token, chatGptAccountId = "local-$token", chatGptRequestJob = job,
    )

    /** Real sockets keep SSE and catalog responses pending while cancellation closes OkHttp transport. */
    private class DelayedServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "chatgpt-test-http").apply { isDaemon = true } }
        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        private val cancellations = ConcurrentHashMap.newKeySet<String>()
        val requestCount = AtomicInteger()
        val catalogStarted = CompletableDeferred<Unit>()
        val client: OkHttpClient

        init {
            server.executor = executor
            server.createContext("/v1") { exchange ->
                val token = exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
                val gate = gates.computeIfAbsent(token) { CountDownLatch(1) }
                requestCount.incrementAndGet()
                try {
                    if (exchange.requestMethod == "GET") {
                        catalogStarted.complete(Unit)
                        gate.await(8, TimeUnit.SECONDS)
                        val catalog = """{"models":[{"slug":"account-model","display_name":"Account model","visibility":"list"}]}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, catalog.size.toLong())
                        exchange.responseBody.write(catalog)
                    } else {
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"delta\":\"Hello\"}\n\n".toByteArray())
                        exchange.responseBody.flush()
                        gate.await(8, TimeUnit.SECONDS)
                        exchange.responseBody.write("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"model\":\"account-model\",\"status\":\"completed\"}}\n\n".toByteArray())
                        exchange.responseBody.flush()
                    }
                } catch (_: Exception) {
                    // A canceled client closes the real socket; shutdown also interrupts waiting handlers.
                } finally {
                    exchange.close()
                }
            }
            server.start()
            client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url("http://127.0.0.1:${server.address.port}${request.url.encodedPath}").build())
            }.eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    call.request().header("Authorization")?.removePrefix("Bearer ")?.let(cancellations::add)
                }
            }).build()
        }

        fun canceled(token: String) = token in cancellations
        fun finish(token: String) { gates.computeIfAbsent(token) { CountDownLatch(1) }.countDown() }
        override fun close() {
            gates.values.forEach { it.countDown() }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
