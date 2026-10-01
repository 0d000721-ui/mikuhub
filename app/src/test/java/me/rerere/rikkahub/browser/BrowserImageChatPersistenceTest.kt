package me.rerere.rikkahub.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowserImageChatPersistenceTest {
    private data class State(val image: Boolean, val text: String = "")

    @Test fun databaseFailureRollsBackOnlyTheUnchangedOptimisticMessage() = runBlocking {
        val previous = State(false)
        val updated = State(true)
        var current = updated
        var retained = false
        val error = runCatching {
            persistBrowserImageMessage(previous, updated, { current }, { current = it }, { it.image }, { retained = it },
                persist = { error("disk full") }, isPersisted = { false })
        }
        assertTrue(error.isFailure)
        assertSame(previous, current)
        assertFalse(retained)
    }

    @Test fun indexingFailureAfterCommitStillKeepsTheDeliveredImage() = runBlocking {
        val previous = State(false)
        val updated = State(true)
        var retained = false
        persistBrowserImageMessage(previous, updated, { updated }, { fail("must not roll back") }, { it.image }, { retained = it },
            persist = { error("index failed after commit") }, isPersisted = { true })
        assertTrue(retained)
    }

    @Test fun concurrentEditIsNotOverwrittenAndItsReferencedImageIsRetained() = runBlocking {
        val updated = State(true)
        val edited = State(true, "user changed this")
        var retained = false
        assertTrue(runCatching {
            persistBrowserImageMessage(State(false), updated, { edited }, { fail("must preserve the edit") }, { it.image }, { retained = it },
                persist = { error("write failed") }, isPersisted = { false })
        }.isFailure)
        assertTrue(retained)
    }

    @Test fun concurrentRemovalAllowsCleanupWithoutReplacingTheNewState() = runBlocking {
        val updated = State(true)
        val edited = State(false, "removed")
        var retained = true
        assertTrue(runCatching {
            persistBrowserImageMessage(State(false), updated, { edited }, { fail("must preserve the edit") }, { it.image }, { retained = it },
                persist = { error("write failed") }, isPersisted = { false })
        }.isFailure)
        assertFalse(retained)
    }

    @Test fun uncertainCommitRetainsFileAndDoesNotClaimSuccess() = runBlocking {
        val updated = State(true)
        var retained = false
        assertTrue(runCatching {
            persistBrowserImageMessage(State(false), updated, { updated }, { fail("commit outcome is unknown") }, { it.image }, { retained = it },
                persist = { error("write interrupted") }, isPersisted = { error("database unavailable") })
        }.isFailure)
        assertTrue(retained)
    }

    @Test fun successfulWriteKeepsTheFileWithoutASecondDatabaseRead() = runBlocking {
        val updated = State(true)
        var retained = false
        persistBrowserImageMessage(State(false), updated, { updated }, { fail("must not roll back") }, { it.image }, { retained = it },
            persist = {}, isPersisted = { error("should not query after success") })
        assertTrue(retained)
    }
}
