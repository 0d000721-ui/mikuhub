package me.rerere.rikkahub.browser

import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Test

class BrowserImageChatRoutingTest {
    @Test fun captureKeepsTheStartingConversation() {
        val routing = BrowserImageChatRouting()
        val first = Uuid.random()
        routing.bind(first)
        val captured = routing.capture()!!
        routing.bind(Uuid.random())
        assertEquals(first, captured.conversationId)
        assertFalse(routing.isCurrent(captured))
    }

    @Test fun anotherToolInTheSameConversationDoesNotInvalidateTheDownload() {
        val routing = BrowserImageChatRouting()
        val id = Uuid.random()
        routing.bind(id)
        val captured = routing.capture()!!
        routing.bind(id)
        assertTrue(routing.isCurrent(captured))
    }

    @Test fun reopeningEvenTheSameChatInvalidatesOlderPendingTransfers() {
        val routing = BrowserImageChatRouting()
        val id = Uuid.random()
        routing.bind(id)
        val captured = routing.capture()!!
        routing.bind(id, restart = true)
        assertFalse(routing.isCurrent(captured))
        assertEquals(id, routing.capture()!!.conversationId)
    }

    @Test fun documentChangeCancelsPendingButAllowsANewDownloadToTheSameChat() {
        val routing = BrowserImageChatRouting()
        val id = Uuid.random()
        routing.bind(id)
        val captured = routing.capture()!!
        routing.cancelPending()
        assertFalse(routing.isCurrent(captured))
        assertEquals(id, routing.capture()!!.conversationId)
        assertTrue(routing.isCurrent(routing.capture()!!))
    }

    @Test fun globalBrowserOrStopCannotReuseAnOldChat() {
        val routing = BrowserImageChatRouting()
        routing.bind(Uuid.random())
        val captured = routing.capture()!!
        routing.clear()
        assertNull(routing.capture())
        assertFalse(routing.isCurrent(captured))
    }

    @Test fun finishingAnOldTransferCannotRedirectToANewChat() {
        val routing = BrowserImageChatRouting()
        routing.bind(Uuid.random())
        val old = routing.capture()!!
        val next = Uuid.random()
        routing.bind(next)
        assertFalse(routing.isCurrent(old))
        assertEquals(next, routing.capture()!!.conversationId)
    }
}
