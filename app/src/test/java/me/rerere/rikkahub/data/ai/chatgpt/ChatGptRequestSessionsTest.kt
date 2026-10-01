package me.rerere.rikkahub.data.ai.chatgpt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptRequestSessionsTest {
    @Test fun signOutImmediatelyCancelsInFlightAccountRequests() {
        val sessions = ChatGptRequestSessions()
        val request = CoroutineScope(sessions.current("one") + Dispatchers.Unconfined).launch { awaitCancellation() }
        sessions.revoke("one")
        assertTrue(request.isCancelled)
        assertTrue(sessions.current("one").isCancelled)
    }

    @Test fun resolveRacingWithSignOutCannotCreateAnActiveReplacement() {
        val sessions = ChatGptRequestSessions()
        sessions.revoke("one")
        val canceled = sessions.current("one")
        repeat(20) {
            assertSame(canceled, sessions.current("one"))
            assertTrue(sessions.current("one").isCancelled)
        }
    }

    @Test fun successfulNewAuthorizationReplacesTheCanceledAccountSession() {
        val sessions = ChatGptRequestSessions()
        val old = sessions.current("one")
        sessions.revoke("one")
        val epoch = sessions.snapshotEpochs().getValue("one")
        sessions.authorized("one", epoch)
        assertNotSame(old, sessions.current("one"))
        assertTrue(sessions.current("one").isActive)
        assertTrue(old.isCancelled)
    }

    @Test fun staleAuthorizationCannotReactivateASignedOutSession() {
        val sessions = ChatGptRequestSessions()
        val initial = sessions.snapshotEpochs()["one"] ?: 0
        sessions.revoke("one")
        assertTrue(runCatching { sessions.authorized("one", initial) }.isFailure)
        assertTrue(sessions.current("one").isCancelled)
    }

    @Test fun reconnectingAnActiveAccountPreservesItsInFlightRequests() {
        val sessions = ChatGptRequestSessions()
        val current = sessions.current("one")
        sessions.authorized("one", 0)
        assertSame(current, sessions.current("one"))
        assertTrue(current.isActive)
    }

    @Test fun signingOutOneAccountDoesNotStopAnotherAccount() {
        val sessions = ChatGptRequestSessions()
        val two = sessions.current("two")
        sessions.revoke("one")
        assertTrue(two.isActive)
        assertFalse(two.isCancelled)
    }
}
