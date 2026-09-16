package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationUpdateTest {
    private fun text(value: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(value)))
    private fun conversation(messages: List<UIMessage>) = Conversation.ofId(Uuid.random(), messages = messages.map { it.toMessageNode() })

    @Test fun identicalUpdateReturnsOriginalConversation() {
        val before = conversation(listOf(text("hello"), text("world")))
        assertSame(before, before.updateCurrentMessages(before.currentMessages.map { it.copy() }))
    }

    @Test fun aThousandMessageHistoryOnlyAllocatesTheChangedNode() {
        val messages = List(1_000) { text("message $it") }
        val before = conversation(messages)
        val after = before.updateCurrentMessages(messages.dropLast(1) + messages.last().copy(parts = listOf(UIMessagePart.Text("new token"))))
        repeat(999) { assertSame(before.messageNodes[it], after.messageNodes[it]) }
        assertNotSame(before.messageNodes.last(), after.messageNodes.last())
        assertEquals("new token", (after.currentMessages.last().parts.single() as UIMessagePart.Text).text)
    }

    @Test fun regenerationKeepsOldBranchAndSelectsNewBranch() {
        val old = text("first")
        val replacement = text("regenerated")
        val before = conversation(listOf(old))
        val after = before.updateCurrentMessages(listOf(replacement))
        assertEquals(listOf(old, replacement), after.messageNodes.single().messages)
        assertEquals(1, after.messageNodes.single().selectIndex)
        assertSame(old, before.currentMessages.single())
    }

    @Test fun existingAlternativeUpdatesWithoutChangingSelection() {
        val selected = text("selected")
        val alternative = text("alternative")
        val before = Conversation.ofId(Uuid.random(), messages = listOf(MessageNode(messages = listOf(selected, alternative))))
        val updated = alternative.copy(parts = listOf(UIMessagePart.Text("changed")))
        val after = before.updateCurrentMessages(listOf(updated))
        assertSame(selected, after.currentMessages.single())
        assertEquals(updated, after.messageNodes.single().messages[1])
    }

    @Test fun appendsNewMessagesAndPreservesUnmentionedTail() {
        val before = conversation(listOf(text("a"), text("b")))
        assertSame(before, before.updateCurrentMessages(listOf(before.currentMessages.first())))
        val appended = text("c")
        val after = before.updateCurrentMessages(before.currentMessages + appended)
        assertEquals(3, after.messageNodes.size)
        assertSame(before.messageNodes[0], after.messageNodes[0])
        assertSame(appended, after.currentMessages.last())
    }

    @Test fun pureTextStreamingDoesNotScanQueuedFiles() {
        val before = conversation(listOf(text("one"), text("two")))
        val after = before.updateCurrentMessages(before.currentMessages.dropLast(1) + before.currentMessages.last().copy(parts = listOf(UIMessagePart.Text("three"))))
        assertTrue(after.removedLocalFileUrls(before) { error("queue must not be scanned") }.isEmpty())
    }

    @Test fun metadataChangesDoNotInspectFiles() {
        val before = conversation(listOf(text("text")))
        assertTrue(before.copy(title = "new title").removedLocalFileUrls(before) { error("must not scan") }.isEmpty())
    }

    @Test fun actualRemovalHonorsOtherBranchesReorderedNodesAndQueue() {
        val image = text("image").copy(parts = listOf(UIMessagePart.Image("file:///a.png")))
        val before = conversation(listOf(image, text("b")))
        val removed = before.copy(messageNodes = before.messageNodes.drop(1))
        assertEquals(setOf("file:///a.png"), removed.removedLocalFileUrls(before))
        assertTrue(removed.removedLocalFileUrls(before) { setOf("file:///a.png") }.isEmpty())
        assertTrue(before.copy(messageNodes = before.messageNodes.reversed()).removedLocalFileUrls(before).isEmpty())
        val alternate = before.copy(messageNodes = listOf(before.messageNodes[0].copy(messages = listOf(image, text("other")), selectIndex = 1)))
        assertTrue(alternate.removedLocalFileUrls(before).isEmpty())
        val moved = removed.copy(messageNodes = removed.messageNodes + image.toMessageNode())
        assertTrue(moved.removedLocalFileUrls(before).isEmpty())
    }

    @Test fun editingTextAroundExistingImageKeepsAttachment() {
        val image = UIMessagePart.Image("file:///a.png")
        val message = text("old").copy(parts = listOf(image, UIMessagePart.Text("old")))
        val before = conversation(listOf(message))
        val after = before.updateCurrentMessages(listOf(message.copy(parts = listOf(image, UIMessagePart.Text("new")))))
        assertTrue(after.removedLocalFileUrls(before) { error("no removal candidate") }.isEmpty())
    }
}
