package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.*
import org.junit.Test

class RequestContextUsageTest {
    private fun request(capacity: Int? = 1_000) = RequestContextUsage(
        modelId = "example", modelName = "Example", contextCapacity = capacity,
        inputMessageCount = 3, conversationMessageCount = 2, retainedMessageCount = 2,
        contextMessageLimit = 0, estimatedTextTokens = 100, textCharacters = 300,
        mediaPartCount = 0, toolCount = 0,
    )

    @Test fun inputOccupancyUsesOneRequestInputAndNotOutputOrCumulativeTotal() {
        val context = request().withUsage(TokenUsage(promptTokens = 200, completionTokens = 400, totalTokens = 600))
        assertEquals(0.2f, context.inputRatio!!, 0.0001f)
        assertEquals(200, context.inputTokens)
    }

    @Test fun unknownCapacityAndMissingInputNeverBecomeZeroPercent() {
        assertNull(request(null).withUsage(TokenUsage(promptTokens = 200)).inputRatio)
        assertNull(request(0).withUsage(TokenUsage(promptTokens = 200)).inputRatio)
        assertNull(request().inputTokens)
        assertNull(request().inputRatio)
        assertNull(request().withUsage(TokenUsage(completionTokens = 8)).inputTokens)
    }

    @Test fun streamUsageBelongsToThisRequestAndDoesNotInheritPreviousToolStep() {
        val previous = UIMessage.assistant("tool result").copy(
            usage = TokenUsage(promptTokens = 900, completionTokens = 50, cachedTokens = 800),
            requestContext = request(),
        )
        val handler = StreamChunkHandler()
        var messages = handler.handle(listOf(previous), StreamChunk.Usage(TokenUsage(completionTokens = 3)))
        assertNull(messages.last().requestContext!!.inputTokens)
        assertEquals(0, messages.last().requestContext!!.providerUsage!!.cachedTokens)
        messages = handler.handle(messages, StreamChunk.Usage(TokenUsage(promptTokens = 250, completionTokens = 12)))
        assertEquals(250, messages.last().requestContext!!.inputTokens)
        assertEquals(12, messages.last().requestContext!!.providerUsage!!.completionTokens)
    }

    @Test fun splitUsageEventsPreserveTheInputFromTheSameRequest() {
        val context = request().withUsage(TokenUsage(promptTokens = 150, cachedTokens = 75))
            .withUsage(TokenUsage(completionTokens = 20))
        assertEquals(150, context.inputTokens)
        assertEquals(75, context.providerUsage!!.cachedTokens)
        assertEquals(20, context.providerUsage!!.completionTokens)
    }

    @Test fun nonStreamingResultUpdatesPreparedContext() {
        val base = listOf(UIMessage.assistant("").copy(requestContext = request()))
        val result = TextGenerationResult(
            id = "response", model = "example",
            message = UIMessage.assistant("answer"),
            usage = TokenUsage(promptTokens = 400, completionTokens = 25),
        )
        val reply = base.handleTextGenerationResult(result).last()
        assertEquals(400, reply.requestContext!!.inputTokens)
        assertEquals("answer", reply.toText())
    }

    @Test fun absentUsageDoesNotInventValuesForNonStreamingResult() {
        val base = listOf(UIMessage.assistant("").copy(
            usage = TokenUsage(promptTokens = 900), requestContext = request(),
        ))
        val result = TextGenerationResult(id = "response", model = "example", message = UIMessage.assistant("answer"))
        assertNull(base.handleTextGenerationResult(result).last().requestContext!!.inputTokens)
    }

    @Test fun oldMessageJsonRemainsReadableAndNewSnapshotRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val original = UIMessage.assistant("before")
        val restored = json.decodeFromString<UIMessage>(json.encodeToString(original))
        assertNull(restored.requestContext)
        val updated = original.copy(requestContext = request().withUsage(TokenUsage(promptTokens = 500)))
        assertEquals(updated, json.decodeFromString<UIMessage>(json.encodeToString(updated)))
    }

    @Test fun continuationStartsWithUnknownInputAndUsesLatestRequestInsteadOfAggregate() {
        val previous = UIMessage.assistant("partial").copy(
            requestContext = request().withUsage(TokenUsage(promptTokens = 100)),
        )
        val handler = StreamChunkHandler()
        val pending = handler.handle(listOf(previous), StreamChunk.Usage(
            usage = TokenUsage(promptTokens = 100), latestRequestUsage = TokenUsage(), requestCount = 2,
        ))
        assertNull(pending.last().requestContext!!.inputTokens)
        val completed = handler.handle(pending, StreamChunk.Usage(
            usage = TokenUsage(promptTokens = 220), latestRequestUsage = TokenUsage(promptTokens = 120), requestCount = 2,
        ))
        assertEquals(220, completed.last().usage!!.promptTokens)
        assertEquals(120, completed.last().requestContext!!.inputTokens)
        assertEquals(0.12f, completed.last().requestContext!!.inputRatio!!, 0.0001f)
    }
}
