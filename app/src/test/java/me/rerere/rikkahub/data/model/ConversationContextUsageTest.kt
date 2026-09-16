package me.rerere.rikkahub.data.model

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationContextUsageTest {
    private val model = Model(modelId = "unknown-test-model", displayName = "Test")
    private fun capture(prepared: List<UIMessage>, original: List<UIMessage> = prepared, limit: Int = 0) =
        captureRequestContext(model, prepared, original, limit, toolCount = 2)

    @Test fun snapshotMeasuresTransformedInputIncludingSystemPromptAndExpandedDocumentText() {
        val original = listOf(UIMessage.user("question"))
        val prepared = listOf(UIMessage.system("abcd"), UIMessage.user("文档内容"))
        val snapshot = capture(prepared, original)
        assertEquals(2, snapshot.inputMessageCount)
        assertEquals(1, snapshot.conversationMessageCount)
        assertEquals(8, snapshot.textCharacters)
        assertEquals(13, snapshot.estimatedTextTokens)
        assertNull(snapshot.inputTokens)
        assertNull(snapshot.contextCapacity)
    }

    @Test fun snapshotRecordsActualStaircaseContextTruncation() {
        val messages = List(10) { UIMessage.user("message $it") }
        val snapshot = capture(messages.takeLast(2), messages, limit = 4)
        assertEquals(10, snapshot.conversationMessageCount)
        assertEquals(2, snapshot.retainedMessageCount)
        assertEquals(4, snapshot.contextMessageLimit)
    }

    @Test fun mediaIsFlaggedWithoutPretendingItsUrlIsTokenizedContent() {
        val image = UIMessagePart.Image("file:///very-long-path-that-is-not-model-text.jpg")
        val tool = UIMessagePart.Tool("call", "tool", "{}", listOf(UIMessagePart.Text("done"), image))
        val snapshot = capture(listOf(UIMessage.user("").copy(parts = listOf(image, tool))))
        assertEquals(2, snapshot.mediaPartCount)
        assertEquals(10, snapshot.textCharacters)
        assertEquals(2, snapshot.toolCount)
    }

    @Test fun changingBranchSelectsItsRequestAndExcludesOtherCandidatesFromTotals() {
        val first = UIMessage.assistant("first").copy(usage = TokenUsage(promptTokens = 1_000, completionTokens = 20))
        val second = UIMessage.assistant("second").copy(usage = TokenUsage(promptTokens = 200, completionTokens = 30))
        val conversation = Conversation.ofId(Uuid.random(), messages = listOf(
            UIMessage.user("hello").toMessageNode(),
            MessageNode(messages = listOf(first, second), selectIndex = 1),
        ))
        val summary = conversation.contextSummary(0)
        assertEquals(second.id, summary.latestMessage!!.id)
        assertEquals(200L, summary.recordedPromptTokens)
        assertEquals(30L, summary.recordedOutputTokens)
        assertEquals(1, summary.recordedUsageMessages)
        assertEquals(2, summary.retainedMessages)
    }

    @Test fun aNewReplyWithoutUsageDoesNotDisplayAnOlderReplyAsCurrent() {
        val previous = UIMessage.assistant("old").copy(usage = TokenUsage(promptTokens = 800))
        val pending = UIMessage.assistant("")
        val conversation = Conversation.ofId(Uuid.random(), messages = listOf(previous, UIMessage.user("new"), pending).map { it.toMessageNode() })
        assertEquals(pending.id, conversation.latestContextMessage()!!.id)
        assertNull(conversation.contextSummary(0).latestMessage!!.usage)
    }

    @Test fun emptyConversationHasNoRequestAndNoFabricatedUsage() {
        val summary = Conversation.ofId(Uuid.random()).contextSummary(4)
        assertNull(summary.latestMessage)
        assertEquals(0, summary.totalMessages)
        assertEquals(0, summary.recordedUsageMessages)
        assertEquals(0L, summary.recordedPromptTokens)
    }
}
