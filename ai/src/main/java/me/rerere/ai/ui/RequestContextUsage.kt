package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge

/** Snapshot of one prepared provider request, independent of conversation-wide token totals. */
@Serializable
data class RequestContextUsage(
    val modelId: String,
    val modelName: String,
    val contextCapacity: Int? = null,
    val inputMessageCount: Int,
    val conversationMessageCount: Int,
    val retainedMessageCount: Int,
    val contextMessageLimit: Int,
    val estimatedTextTokens: Int,
    val textCharacters: Int,
    val mediaPartCount: Int,
    val toolCount: Int,
    val providerUsage: TokenUsage? = null,
    val providerRequestCount: Int = 1,
) {
    val inputTokens: Int? get() = providerUsage?.promptTokens?.takeIf { it > 0 }
    val inputRatio: Float?
        get() = contextCapacity?.takeIf { it > 0 }?.let { capacity ->
            inputTokens?.toFloat()?.div(capacity)
        }

    fun withUsage(usage: TokenUsage?, requestCount: Int = providerRequestCount): RequestContextUsage {
        val previous = providerUsage.takeIf { requestCount == providerRequestCount }
        return copy(
            providerUsage = usage?.let { previous.merge(it) } ?: previous,
            providerRequestCount = requestCount,
        )
    }
}
