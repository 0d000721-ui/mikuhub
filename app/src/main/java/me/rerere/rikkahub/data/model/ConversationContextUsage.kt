package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.RequestContextUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext

/** Run once after the input transformers, never for individual streamed text deltas. */
internal fun captureRequestContext(
    model: Model,
    preparedMessages: List<UIMessage>,
    conversationMessages: List<UIMessage>,
    contextMessageLimit: Int,
    toolCount: Int,
): RequestContextUsage {
    var asciiCharacters = 0L
    var otherCharacters = 0L
    var mediaParts = 0
    fun countText(text: String) {
        text.forEach { if (it.code < 128) asciiCharacters++ else otherCharacters++ }
    }
    fun countParts(parts: List<UIMessagePart>) {
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> countText(part.text)
                is UIMessagePart.Tool -> {
                    countText(part.toolName)
                    countText(part.input)
                    countParts(part.output)
                }
                is UIMessagePart.ServerTool -> {
                    countText(part.toolName)
                    part.input?.let { countText(it.toString()) }
                    part.output?.let { countText(it.toString()) }
                }
                is UIMessagePart.Image, is UIMessagePart.Audio,
                is UIMessagePart.Video, is UIMessagePart.Document -> mediaParts++
                // Thinking fields may be omitted or represented as opaque provider state.
                else -> Unit
            }
        }
    }
    preparedMessages.forEach { countParts(it.parts) }
    return RequestContextUsage(
        modelId = model.modelId,
        modelName = model.displayName.ifBlank { model.modelId },
        contextCapacity = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(model.modelId)?.takeIf { it > 0 },
        inputMessageCount = preparedMessages.size,
        conversationMessageCount = conversationMessages.size,
        retainedMessageCount = conversationMessages.limitContext(contextMessageLimit).size,
        contextMessageLimit = contextMessageLimit,
        // A deliberately labelled text heuristic, not a model-specific tokenizer.
        estimatedTextTokens = ((asciiCharacters + 3) / 4 + otherCharacters + preparedMessages.size * 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        textCharacters = (asciiCharacters + otherCharacters).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        mediaPartCount = mediaParts,
        toolCount = toolCount,
    )
}

/** Selecting another candidate reply selects that reply's request data too. */
internal fun Conversation.latestContextMessage(): UIMessage? =
    messageNodes.asReversed().firstOrNull { it.currentMessage.role == MessageRole.ASSISTANT }?.currentMessage

internal data class ConversationContextSummary(
    val totalMessages: Int,
    val retainedMessages: Int,
    val contextMessageLimit: Int,
    val latestMessage: UIMessage?,
    val recordedPromptTokens: Long,
    val recordedOutputTokens: Long,
    val recordedUsageMessages: Int,
)

internal fun Conversation.contextSummary(contextMessageLimit: Int): ConversationContextSummary {
    val selected = currentMessages
    val recorded = selected.filter { it.role == MessageRole.ASSISTANT }.mapNotNull { it.usage }
    return ConversationContextSummary(
        totalMessages = selected.size,
        retainedMessages = selected.limitContext(contextMessageLimit).size,
        contextMessageLimit = contextMessageLimit,
        latestMessage = latestContextMessage(),
        recordedPromptTokens = recorded.sumOf { it.promptTokens.toLong() },
        recordedOutputTokens = recorded.sumOf { it.completionTokens.toLong() },
        recordedUsageMessages = recorded.size,
    )
}
